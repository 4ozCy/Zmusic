#!/usr/bin/env python3
"""
Turns the astronaut model (a binary FBX exported from Blender) into app/src/main/assets/astronaut.mesh,
the small file the app draws with OpenGL ES (see ui/components/AstronautMesh.kt).

    python3 scripts/astronaut_mesh.py path/to/Astronaut.fbx

Needs numpy. Only the parts of FBX this model uses are read: triangle or polygon meshes with
normals per corner and one material per polygon. Textures, cameras, lights and animation are
ignored; the model has none.

The model comes standing in an A-pose. The app bends it into a floating crouch and makes it wave,
so this script also rigs it: every vertex gets up to two bones and a weight. The weights come from
how close the vertex is to each bone's capsule, blended smoothly where two meet. They depend only on
where a vertex is, so the separate shells of the model (straps, patches, the wrist display) bend
with the suit under them instead of pulling away from it. The joint positions in JOINTS are measured
on this model; if it is replaced with one of other proportions, measure them again.

File layout, little endian:

    "ZAST", u32 version (1), u32 vertex count, u32 index count, u32 material count, u32 joint count
    f32 x 3 position scale, f32 x 3 position offset     a vertex is offset + scale * snorm16 position
    f32 x 4 frame centre and radius                     the point it turns about, and how far it reaches
    f32 x 3 per joint, in JOINT_ORDER
    f32 x 8 per material: linear albedo rgb, gloss exponent, reflection, kind, 0, 0
        kind 0 is suit, 1 the gold visor, 2 a helmet lamp
    16 bytes per vertex:
        snorm16 x 3 position, u8 first bone, u8 second bone,
        snorm8 x 3 normal, u8 weight of the first bone (0..255),
        u8 material, u8 x 3 padding
    u16 per index, three per triangle

Positions are in model units: the model is one unit tall, y up, facing +z, its left hand on +x.
"""

import struct
import sys
import zlib
from pathlib import Path

import numpy as np

OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/astronaut.mesh"

# Measured on Astronaut_v1_edit.fbx, in its own units (feet at y = 0, one unit tall). The figure's
# left side is +x; the right side mirrors it.
JOINTS = {
    "neck": (0.0, 0.80, 0.02),
    "head": (0.0, 0.895, 0.04),
    "shoulderL": (0.175, 0.755, 0.03),
    "elbowL": (0.235, 0.655, 0.06),
    "wristL": (0.29, 0.555, 0.11),
    "fingersL": (0.322, 0.465, 0.125),
    "hipL": (0.085, 0.47, 0.04),
    "kneeL": (0.09, 0.29, 0.065),
    "ankleL": (0.11, 0.09, 0.03),
    "toeL": (0.125, 0.03, 0.11),
    # Where the jet puffs come from, under the life-support pack, and the way they blow.
    "puffL": (0.11, 0.55, -0.13),
    "puffAimL": (0.30, 0.30, -0.36),
}
for name, (x, y, z) in list(JOINTS.items()):
    if name.endswith("L"):
        JOINTS[name[:-1] + "R"] = (-x, y, z)

# The points the app needs, in the order it reads them (AstronautMesh.J_*).
JOINT_ORDER = [
    "neck", "shoulderL", "elbowL", "shoulderR", "elbowR", "hipL", "kneeL", "hipR", "kneeR",
    "wristL", "wristR", "puffR", "puffAimR",
]

# Bones, in the order the app's skinning matrices are in (AstronautRig.B_*).
BONES = ["root", "head", "upperArmL", "foreArmL", "upperArmR", "foreArmR", "thighL", "shinL", "thighR", "shinR"]

# Each bone's capsules: (from, to, radius). The torso is three, so the shoulders and the pelvis
# stay with it.
CAPSULES = {
    "root": [
        ((0.0, 0.50, 0.0), (0.0, 0.70, 0.0), 0.15),
        ((-0.12, 0.735, 0.02), (0.12, 0.735, 0.02), 0.07),
        ((-0.05, 0.48, 0.03), (0.05, 0.48, 0.03), 0.085),
    ],
    "head": [(JOINTS["head"], JOINTS["head"], 0.105)],
}
for s in "LR":
    j = lambda n: JOINTS[n + s]
    CAPSULES["upperArm" + s] = [(j("shoulder"), j("elbow"), 0.055)]
    CAPSULES["foreArm" + s] = [(j("elbow"), j("wrist"), 0.05), (j("wrist"), j("fingers"), 0.05)]
    CAPSULES["thigh" + s] = [(j("hip"), j("knee"), 0.07)]
    CAPSULES["shin" + s] = [(j("knee"), j("ankle"), 0.06), (j("ankle"), j("toe"), 0.055)]

# How soft the hand-over between two bones is, in model units. Smaller is a sharper crease.
BLEND = 0.012

# Where the figure turns about (about the chest unit), and the reach the app frames it for: the
# rest pose's own reach plus room for the waving hand and the jet-pack puff.
CENTRE = (0.0, 0.56, 0.0)
FRAME_MARGIN = 1.12

KIND_SUIT, KIND_VISOR, KIND_LAMP = 0, 1, 2


def read_fbx(path):
    """The binary FBX node tree: dicts of name, props and kids."""
    data = Path(path).read_bytes()
    if data[:21] != b"Kaydara FBX Binary  \x00":
        sys.exit("Not a binary FBX file. Export it from Blender as binary FBX.")
    version = struct.unpack_from("<I", data, 23)[0]
    wide = version >= 7500

    def prop(pos):
        t = chr(data[pos])
        pos += 1
        scalar = {"Y": "<h", "C": "<?", "I": "<i", "F": "<f", "D": "<d", "L": "<q"}
        if t in scalar:
            fmt = scalar[t]
            return struct.unpack_from(fmt, data, pos)[0], pos + struct.calcsize(fmt)
        if t in "fdlib":
            n, enc, size = struct.unpack_from("<III", data, pos)
            pos += 12
            raw = data[pos:pos + size]
            if enc == 1:
                raw = zlib.decompress(raw)
            dtype = {"f": "<f4", "d": "<f8", "l": "<i8", "i": "<i4", "b": "u1"}[t]
            return np.frombuffer(raw, dtype=dtype, count=n), pos + size
        if t in "SR":
            n = struct.unpack_from("<I", data, pos)[0]
            raw = data[pos + 4:pos + 4 + n]
            return (raw.decode("utf8", "replace") if t == "S" else raw), pos + 4 + n
        raise ValueError(f"unknown FBX property type {t!r}")

    def node(pos):
        if wide:
            end, count, _ = struct.unpack_from("<QQQ", data, pos)
            pos += 24
        else:
            end, count, _ = struct.unpack_from("<III", data, pos)
            pos += 12
        name = data[pos + 1:pos + 1 + data[pos]].decode()
        pos += 1 + data[pos]
        if end == 0:
            return None, pos
        props = []
        for _ in range(count):
            value, pos = prop(pos)
            props.append(value)
        kids = []
        sentinel = 25 if wide else 13
        while pos < end - sentinel:
            kid, pos = node(pos)
            if kid is None:
                break
            kids.append(kid)
        return {"name": name, "props": props, "kids": kids}, end

    nodes, pos = [], 27
    while pos < len(data):
        n, pos = node(pos)
        if n is None:
            break
        nodes.append(n)
    return nodes


def kids(n, name):
    return [k for k in n["kids"] if k["name"] == name]


def kid(n, name):
    return kids(n, name)[0]


def material_info(m):
    name = m["props"][1].split("\x00")[0]
    props = {p["props"][0]: p["props"][4:] for p in kids(kid(m, "Properties70"), "P")}
    albedo = props.get("DiffuseColor", (0.8, 0.8, 0.8))
    gloss = props.get("ShininessExponent", (20.0,))[0]
    reflect = props.get("ReflectionFactor", (0.0,))[0]
    lower = name.lower()
    kind = KIND_VISOR if "visor" in lower else KIND_LAMP if "lamp" in lower else KIND_SUIT
    return name, [*albedo, gloss, reflect, kind, 0.0, 0.0]


def load(path):
    nodes = read_fbx(path)
    objects = next(n for n in nodes if n["name"] == "Objects")
    connections = next(n for n in nodes if n["name"] == "Connections")
    links = [c["props"][1:3] for c in kids(connections, "C")]
    by_id = {o["props"][0]: o for o in objects["kids"]}

    materials, slot_of = [], {}
    positions, normals, mats, tris, part = [], [], [], [], []
    base = 0
    for gi, model in enumerate(kids(objects, "Model")):
        mid = model["props"][0]
        geometry = next(by_id[c] for c, p in links if p == mid and by_id.get(c, {}).get("name") == "Geometry")
        # A model's materials, in connection order, are what its polygon material indices mean.
        local = []
        for c, p in links:
            if p == mid and by_id.get(c, {}).get("name") == "Material":
                name, info = material_info(by_id[c])
                if name not in slot_of:
                    slot_of[name] = len(materials)
                    materials.append((name, info))
                local.append(slot_of[name])
        local = np.array(local)

        props = {p["props"][0]: p["props"][4:] for p in kids(kid(model, "Properties70"), "P")}
        if np.any(np.abs(np.array(props.get("Lcl Rotation", (0, 0, 0)))) > 0.01):
            sys.exit("The model is rotated in the FBX. Apply its rotation in Blender before exporting.")
        # Blender writes centimetres as a scale of 100 on each object. Only the parts' sizes
        # relative to each other matter here: the whole figure is scaled to one unit tall below.
        scale = np.array(props.get("Lcl Scaling", (1.0, 1.0, 1.0)))
        move = np.array(props.get("Lcl Translation", (0.0, 0.0, 0.0)))

        v = kid(geometry, "Vertices")["props"][0].reshape(-1, 3) * scale + move
        poly = kid(geometry, "PolygonVertexIndex")["props"][0]
        layer = kid(geometry, "LayerElementNormal")
        if kid(layer, "MappingInformationType")["props"][0] != "ByPolygonVertex":
            sys.exit("Expected normals per polygon corner. Export with smoothing set to Normals Only.")
        nrm = kid(layer, "Normals")["props"][0].reshape(-1, 3)
        if kids(layer, "NormalsIndex"):
            nrm = nrm[kid(layer, "NormalsIndex")["props"][0]]
        poly_mat = local[kid(kid(geometry, "LayerElementMaterial"), "Materials")["props"][0]]

        corner = np.where(poly < 0, ~poly, poly)
        ends = np.where(poly < 0)[0]
        starts = np.concatenate([[0], ends[:-1] + 1])
        cmat = np.repeat(poly_mat, ends - starts + 1)
        # One vertex per distinct position, normal and material.
        key = np.concatenate([corner[:, None], np.round(nrm * 1000).astype(np.int64), cmat[:, None]], 1)
        _, first, which = np.unique(key, axis=0, return_index=True, return_inverse=True)
        which = which.reshape(-1)
        fan = [(which[s], which[k], which[k + 1]) for s, e in zip(starts, ends) for k in range(s + 1, e)]
        positions.append(v[corner[first]])
        normals.append(nrm[first])
        mats.append(cmat[first])
        tris.append(np.array(fan) + base)
        part.append(np.full(len(first), gi))
        base += len(first)

    p = np.concatenate(positions)
    # One unit tall, feet at y = 0, centred left to right: the units JOINTS are measured in.
    p[:, 1] -= p[:, 1].min()
    p /= p[:, 1].max()
    p[:, 0] -= (p[:, 0].min() + p[:, 0].max()) / 2
    n = np.concatenate(normals)
    n /= np.linalg.norm(n, axis=1, keepdims=True)
    return p, n, np.concatenate(mats), np.concatenate(tris), np.concatenate(part), materials


def segment_distance(p, a, b):
    a, b = np.asarray(a, float), np.asarray(b, float)
    ab = b - a
    t = np.clip(((p - a) @ ab) / max(ab @ ab, 1e-12), 0.0, 1.0)
    return np.linalg.norm(p - (a + t[:, None] * ab), axis=1)


def rig(p, part):
    # How far each vertex is outside each bone's nearest capsule; the closest bones win, softly.
    reach = np.stack([np.min([segment_distance(p, a, b) - r for a, b, r in CAPSULES[name]], axis=0) for name in BONES], 1)
    w = np.exp(-(reach - reach.min(1, keepdims=True)) / BLEND)
    # The second mesh is the life-support pack on his back, which is part of the torso.
    pack = part == 1
    w[pack] = 0.0
    w[pack, 0] = 1.0
    order = np.argsort(-w, axis=1)
    first, second = order[:, 0], order[:, 1]
    rows = np.arange(len(p))
    w0, w1 = w[rows, first], w[rows, second]
    weight = w0 / (w0 + w1)
    # Where the first bone has it all, point the second at the same bone so nothing is shared.
    second = np.where(weight > 0.996, first, second)
    return first, second, np.where(weight > 0.996, 1.0, weight)


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    p, n, mat, tri, part, materials = load(sys.argv[1])
    if len(p) >= 65536:
        sys.exit(f"{len(p)} vertices is too many for 16-bit indices. Decimate the model in Blender first.")
    b0, b1, weight = rig(p, part)

    centre = np.array(CENTRE)
    radius = float(np.linalg.norm(p - centre, axis=1).max()) * FRAME_MARGIN
    lo, hi = p.min(0), p.max(0)
    offset = (lo + hi) / 2
    scale = (hi - lo) / 2

    vertices = np.zeros(len(p), dtype=[
        ("pos", "<i2", 3), ("bones", "u1", 2), ("normal", "i1", 3), ("weight", "u1"),
        ("material", "u1"), ("pad", "u1", 3),
    ])
    vertices["pos"] = np.round((p - offset) / scale * 32767).clip(-32767, 32767)
    vertices["bones"] = np.stack([b0, b1], 1)
    vertices["normal"] = np.round(n * 127).clip(-127, 127)
    vertices["weight"] = np.round(weight * 255)
    vertices["material"] = mat
    assert vertices.itemsize == 16

    out = bytearray()
    out += b"ZAST" + struct.pack("<5I", 1, len(p), tri.size, len(materials), len(JOINT_ORDER))
    out += struct.pack("<3f", *scale) + struct.pack("<3f", *offset)
    out += struct.pack("<4f", *centre, radius)
    for name in JOINT_ORDER:
        out += struct.pack("<3f", *JOINTS[name])
    for _, info in materials:
        out += struct.pack("<8f", *info)
    out += vertices.tobytes()
    out += tri.astype("<u2").tobytes()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(bytes(out))

    print(f"{len(p)} vertices, {len(tri)} triangles, {len(materials)} materials, frame radius {radius:.3f}")
    for i, (name, info) in enumerate(materials):
        print(f"  {i:2d} {name}  kind {int(info[5])}")
    print(f"Wrote {OUT} ({len(out) // 1024} KB)")


if __name__ == "__main__":
    main()

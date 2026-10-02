"""Generate Codec2 C codebooks without executing a host C compiler.

Equivalent to upstream src/generate_codebook.c. Generated files are build inputs
for Android/iOS cross compilation and intentionally live outside the submodule.
"""
from pathlib import Path
import math
import re

ROOT = Path(__file__).resolve().parents[1]
INPUT = ROOT / "third_party" / "codec2" / "src" / "codebook"
OUTPUT = ROOT / "composeApp" / "src" / "nativeInterop" / "codec2-generated"

SETS = {
    "codebook.c": ("lsp_cb", [f"lsp{i}.txt" for i in range(1, 11)]),
    "codebookd.c": ("lsp_cbd", [f"dlsp{i}.txt" for i in range(1, 11)]),
    "codebookjmv.c": ("lsp_cbjmv", [f"lspjmv{i}.txt" for i in range(1, 4)]),
    "codebookge.c": ("ge_cb", ["gecb.txt"]),
    "codebooknewamp1.c": ("newamp1vq_cb", ["train_120_1.txt", "train_120_2.txt"]),
    "codebooknewamp1_energy.c": ("newamp1_energy_cb", ["newamp1_energy_q.txt"]),
    "codebooknewamp2.c": ("newamp2vq_cb", ["codes_450.txt"]),
    "codebooknewamp2_energy.c": ("newamp2_energy_cb", ["newamp2_energy_q.txt"]),
}

def load(name):
    text = "\n".join(line.split("#", 1)[0] for line in (INPUT / name).read_text().splitlines())
    values = [float(v) for v in re.findall(r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?", text)]
    k, m = int(values[0]), int(values[1])
    data = values[2:]
    if len(data) != k * m:
        raise ValueError(f"{name}: expected {k*m} values, got {len(data)}")
    return k, m, data

def generate(symbol, names):
    books = [load(name) for name in names]
    out = ["/* Generated from Codec2 LGPL codebooks; do not edit. */", '#include "defines.h"', ""]
    for index, (name, (k, _, data)) in enumerate(zip(names, books)):
        out += [f"/* {name} */", "#ifdef __EMBEDDED__", f"static const float codes{index}[] = {{", "#else", f"static float codes{index}[] = {{", "#endif"]
        for start in range(0, len(data), k):
            out.append("  " + ", ".join(format(v, ".9g") for v in data[start:start+k]) + ("," if start+k < len(data) else ""))
        out += ["};", ""]
    out += [f"const struct lsp_codebook {symbol}[] = {{"]
    for index, (name, (k, m, _)) in enumerate(zip(names, books)):
        out.append(f"  /* {name} */ {{ {k}, {round(math.log2(m))}, {m}, codes{index} }},")
    out += ["  { 0, 0, 0, 0 }", "};", ""]
    return "\n".join(out)

OUTPUT.mkdir(parents=True, exist_ok=True)
for filename, (symbol, names) in SETS.items():
    (OUTPUT / filename).write_text(generate(symbol, names), newline="\n")

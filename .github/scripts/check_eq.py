"""Checks eq.json the same way the apps do (EqRemote.kt / EqRemote.swift). The apps ship a copy of
it and have no EQ values of their own, so a broken file must never be built or released."""
import json
import sys

j = json.load(open(sys.argv[1]))
assert j.get("schema") == 1, "schema must be 1"
assert isinstance(j.get("version"), str) and j["version"], "version is missing"
assert isinstance(j.get("updatedAt"), str) and j["updatedAt"], "updatedAt is missing"
for pid in ["movie", "pop", "rock", "classical"]:
    p = j["presets"][pid]
    assert -6 <= p["preamp"] <= 3, f"{pid}: preamp out of range"
    if "factory" in p:
        assert p["factory"] in (1, 2, 3, 4, 5), f"{pid}: factory must be 1 to 5"
    if "about" in p:
        assert isinstance(p["about"], str), f"{pid}: about must be text"
    bands = p["bands"]
    assert len(bands) == 8, f"{pid}: needs exactly 8 bands"
    for i, b in enumerate(bands):
        assert b["type"] in (0, 1, 2), f"{pid} band {i}: type"
        assert -12 <= b["gain"] <= 12, f"{pid} band {i}: gain"
        assert 20 <= b["freq"] <= 20000, f"{pid} band {i}: freq"
        assert 0.1 <= b["q"] <= 10, f"{pid} band {i}: q"
print(f"eq.json {j['version']} is valid")

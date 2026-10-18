"""Drives a full run against a live server with fake phone/XR/PI clients.

Usage: .venv/bin/python simulate.py [http://localhost:8000] [--delay 1.5]
Use --delay to watch it play out on the web app.
"""
import argparse
import asyncio
import base64
import io
import json
import random

import httpx
import websockets

# Barnase / barstar (PDB 1BRS chains A/D) - matches web/public/structures/demo_complex.pdb.
TARGET = ("AQVINTFDGVADYLQTYHKLPDNYITKSEAQALGWVASKGNLADVAPGKSIGGDIFSNREGKLPGKSGRTWREADINYTSGFRNSDRIL"
          "YSSDWLIYKTTDHYQTFTKIR")
BINDER = "KKAVINGEQIRSISDLHQTLKKELALPEYYGENLDALWDCLTGWVEYPLVLEWRQFEQSKQLTENGAESVLQVFREAKAEGCDITIILS"


def tiny_jpeg(color: str = "#4a7") -> str:
    """Placeholder frame so evidence PDFs have an image without needing a camera."""
    from PIL import Image  # Pillow ships with reportlab

    buf = io.BytesIO()
    Image.new("RGB", (320, 240), color).save(buf, "JPEG")
    return base64.b64encode(buf.getvalue()).decode()


async def main(base: str, delay: float) -> None:
    ws_url = base.replace("http", "ws") + "/ws?client=xr"
    async with httpx.AsyncClient(base_url=base, timeout=10) as c, websockets.connect(ws_url) as xr:
        async def pause():
            await asyncio.sleep(delay)

        # (size, backend, p50_ms, peak_rss_mb, status, error): NPU runs out at 3B, CPU 4-bit survives.
        ladder = [("8M", 4.1, 60), ("35M", 9.8, 180), ("150M", 31.0, 640), ("650M", 118.0, 1900)]
        rows = [(s, "qnn", p, m, "ok", None) for s, p, m in ladder]
        rows += [(s, "xnnpack", p * 3.2, m * 0.6, "ok", None) for s, p, m in ladder]
        rows += [("3B", "qnn", None, None, "oom", "HTP context too large"),
                 ("3B", "xnnpack", 1480.0, 2300, "ok", None)]
        for size, backend, p50, rss, status, err in rows:
            r = await c.post("/bench", json={
                "device": "s25", "model": size, "backend": backend,
                "load_ms": p50 and p50 * 20, "p50_ms": p50, "p95_ms": p50 and p50 * 1.2,
                "peak_rss_mb": rss, "thermal_headroom": round(random.uniform(0.3, 0.9), 2),
                "status": status, "error": err,
            })
            r.raise_for_status()

        r = await c.post("/binding", json={
            "target_seq": TARGET, "binder_seq": BINDER, "model": "650M", "backend": "qnn",
            "score": 0.87, "target_residues": [27, 59, 73, 83, 87, 102],
            "binder_residues": [29, 35, 38, 39, 76], "latency_ms": 236,
            "structure": "/structures/demo_complex.pdb",
        })
        r.raise_for_status()
        run_id = r.json()["run_id"]
        print("run", run_id, r.json()["state"])
        await pause()

        print("handoff", (await c.post(f"/lab/handoff/{run_id}")).json())
        await pause()
        steps = (await c.post(f"/lab/enter/{run_id}")).json()["steps"]

        # The headset should have received the protocol over WS.
        while True:
            msg = json.loads(await xr.recv())
            if msg["type"] == "protocol":
                break
        assert [s["id"] for s in msg["steps"]] == [s["id"] for s in steps]

        for i, step in enumerate(steps):
            dets = [{"label": l, "conf": 0.9, "box": [0.3, 0.3, 0.2, 0.2]} for l in step["required"]]
            await xr.send(json.dumps({"type": "detections", "run_id": run_id,
                                      "step_id": step["id"], "detections": dets}))
            if i == 1:  # Inject one anomaly to show the red card.
                r = await c.post("/lab/step", json={
                    "run_id": run_id, "step_id": step["id"], "status": "anomaly",
                    "detections": [{"label": "scissors", "conf": 0.8, "box": [0.6, 0.5, 0.1, 0.1]}],
                    "snapshot_b64": tiny_jpeg("#c33")})
                r.raise_for_status()
                await pause()
            r = await c.post("/lab/step", json={"run_id": run_id, "step_id": step["id"],
                                                "status": "passed", "detections": dets,
                                                "snapshot_b64": tiny_jpeg()})
            r.raise_for_status()
            print("step", step["id"], r.json()["state"])
            await pause()

        print("approve", (await c.post(f"/approve/{run_id}", json={"approver": "Dr. Demo"})).json())
        while json.loads(await xr.recv())["type"] != "beam":
            pass
        print("beam received by xr")
        await pause()
        ev = (await c.post(f"/evidence/{run_id}")).json()
        print("evidence", ev)
        await pause()
        pat = (await c.post(f"/patent/{run_id}")).json()
        print("patent", pat.get("pdf", pat))
        for url in (ev["pdf"], pat["pdf"]):
            r = await c.get(url)
            assert r.status_code == 200 and r.content[:4] == b"%PDF", url
        final = (await c.get(f"/runs/{run_id}")).json()
        assert final["state"] == "PATENT_DRAFT" and final["chain_ok"], final["state"]
        print("OK", run_id, final["state"], f"{len(final['events'])} events, chain verified")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("base", nargs="?", default="http://localhost:8000")
    ap.add_argument("--delay", type=float, default=0)
    a = ap.parse_args()
    asyncio.run(main(a.base, a.delay))

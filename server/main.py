"""BindLab XR backend: REST + WebSocket hub tying phone, headset and web together.

Run: .venv/bin/uvicorn main:app --host 0.0.0.0 --port 8000
"""
import uuid
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import db
import evidence
import lab_handoff
import workflow as wf

ROOT = Path(__file__).parent.parent

app = FastAPI(title="BindLab XR")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


# ---------------------------------------------------------------- WebSocket hub

class Hub:
    def __init__(self) -> None:
        self.clients: dict[WebSocket, str] = {}

    async def send(self, msg: dict, to: set[str] | None = None) -> None:
        for ws, kind in list(self.clients.items()):
            if to is None or kind in to:
                try:
                    await ws.send_json(msg)
                except Exception:
                    self.clients.pop(ws, None)


hub = Hub()


async def set_state(run_id: str, state: str, **kw) -> dict:
    run = db.update_run(run_id, state=state, **kw)
    db.add_event(run_id, f"state:{state}", {"state": state, "step_index": run["step_index"]})
    await hub.send({"type": "state", "run_id": run_id, "state": state,
                    "step_index": run["step_index"]})
    return run


def load_run(run_id: str) -> dict:
    run = db.get_run(run_id)
    if run is None:
        raise HTTPException(404, f"run {run_id} not found")
    return run


def guard(run: dict, *allowed: str) -> None:
    try:
        wf.require(run["state"], *allowed)
    except wf.TransitionError as e:
        raise HTTPException(409, str(e))


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket, client: str = "web"):
    await ws.accept()
    hub.clients[ws] = client
    try:
        while True:
            msg = await ws.receive_json()
            kind = msg.get("type")
            if kind == "detections":
                # Mirror the headset's live detections to the web "Lab Live" page.
                await hub.send({**msg, "from": client}, to={"web"})
            elif kind == "haptic":
                await hub.send(msg, to={"phone", "xr"} - {client})
    except WebSocketDisconnect:
        pass
    finally:
        hub.clients.pop(ws, None)


# ---------------------------------------------------------------- Benchmarks

class Bench(BaseModel):
    device: Literal["s25", "xr"]
    model: str
    backend: Literal["qnn", "xnnpack"]
    load_ms: float | None = None
    p50_ms: float | None = None
    p95_ms: float | None = None
    peak_rss_mb: float | None = None
    thermal_headroom: float | None = None
    battery_delta: float | None = None
    sustained_s: float | None = None
    status: Literal["ok", "oom", "load_failed"] = "ok"
    error: str | None = None


@app.post("/bench")
async def post_bench(b: Bench):
    db.add_bench(b.model_dump())
    await hub.send({"type": "bench", **b.model_dump()}, to={"web"})
    return {"ok": True}


@app.get("/bench")
async def get_bench():
    return db.list_bench()


# ---------------------------------------------------------------- Binding + lab handoff

class Binding(BaseModel):
    target_seq: str
    binder_seq: str
    model: str
    backend: Literal["qnn", "xnnpack"]
    score: float
    target_residues: list[int] = Field(default_factory=list)
    binder_residues: list[int] = Field(default_factory=list)
    latency_ms: float | None = None
    structure: str | None = None  # e.g. "/structures/demo_complex.pdb"


@app.post("/binding")
async def post_binding(b: Binding):
    run_id = uuid.uuid4().hex[:8]
    db.create_run(run_id, "BINDING", b.model_dump())
    db.add_event(run_id, "binding", b.model_dump())
    run = await set_state(run_id, wf.binding_state(b.score))
    return {"run_id": run_id, "state": run["state"]}


@app.post("/lab/handoff/{run_id}")
async def post_handoff(run_id: str):
    run = load_run(run_id)
    guard(run, "BIND_OK")
    result = lab_handoff.send(run)
    db.add_event(run_id, "lab_handoff", result)
    await set_state(run_id, "SENT_TO_LAB", data_patch={"handoff": result})
    return result


@app.post("/lab/enter/{run_id}")
async def post_enter(run_id: str):
    run = load_run(run_id)
    guard(run, "SENT_TO_LAB")
    await set_state(run_id, "LAB_ENTERED")
    await hub.send({"type": "protocol", "run_id": run_id, "name": wf.PROTOCOL["name"],
                    "steps": wf.PROTOCOL["steps"], "coco_to_lab": wf.PROTOCOL["coco_to_lab"]})
    run = await set_state(run_id, wf.step_state(0), step_index=0)
    return {"state": run["state"], "steps": wf.PROTOCOL["steps"]}


class Detection(BaseModel):
    label: str
    conf: float
    box: list[float]


class Step(BaseModel):
    run_id: str
    step_id: str
    status: Literal["passed", "anomaly"]
    detections: list[Detection] = Field(default_factory=list)
    snapshot_b64: str | None = None


@app.post("/lab/step")
async def post_step(s: Step):
    run = load_run(s.run_id)
    guard(run, "STEP")
    step = wf.current_step(run["step_index"])
    if step is None or step["id"] != s.step_id:
        raise HTTPException(409, f"current step is {step and step['id']}, got {s.step_id}")
    snap = evidence.save_snapshot(s.run_id, f"{s.step_id}_{s.status}", s.snapshot_b64)
    db.add_event(s.run_id, f"step:{s.step_id}:{s.status}",
                 {"detections": [d.model_dump() for d in s.detections], "snapshot": snap})
    if s.status == "anomaly":
        await hub.send({"type": "haptic", "pattern": "anomaly"}, to={"phone"})
        await hub.send({"type": "anomaly", "run_id": s.run_id, "step_id": s.step_id,
                        "detections": [d.model_dump() for d in s.detections]})
        return {"state": run["state"]}
    state, idx = wf.advance_step(run["step_index"])
    run = await set_state(s.run_id, state, step_index=idx)
    return {"state": run["state"]}


# ---------------------------------------------------------------- Approval, evidence, patent

class Approval(BaseModel):
    approver: str = "PI"
    approved: bool = True
    note: str | None = None


@app.post("/approve/{run_id}")
async def post_approve(run_id: str, a: Approval):
    run = load_run(run_id)
    guard(run, "APPROVAL")
    db.add_event(run_id, "approval", a.model_dump())
    if not a.approved:
        return {"state": run["state"]}
    run = await set_state(run_id, "BEAMED", data_patch={"approval": a.model_dump()})
    # "Beam" the approved payload to the headset.
    await hub.send({"type": "beam", "run_id": run_id, "approval": a.model_dump(),
                    "score": run["data"]["score"]}, to={"xr"})
    return {"state": run["state"]}


@app.post("/evidence/{run_id}")
async def post_evidence(run_id: str):
    run = load_run(run_id)
    guard(run, "BEAMED", "EVIDENCE", "PATENT_DRAFT")
    if wf.phase(run["state"]) == "BEAMED":
        run = await set_state(run_id, "EVIDENCE")
    evidence.evidence_pdf(run)
    return {"pdf": f"/evidence/{run_id}.pdf", "chain_ok": db.verify_chain(run_id)}


@app.get("/evidence/{run_id}.pdf")
async def get_evidence_pdf(run_id: str):
    path = evidence.REPORTS / f"{run_id}_evidence.pdf"
    if not path.exists():
        raise HTTPException(404, "evidence not generated yet")
    return FileResponse(path, media_type="application/pdf")


@app.post("/patent/{run_id}")
async def post_patent(run_id: str):
    run = load_run(run_id)
    guard(run, "EVIDENCE", "PATENT_DRAFT")
    if run["state"] == "EVIDENCE":
        run = await set_state(run_id, "PATENT_DRAFT")
    evidence.patent_pdf(run)
    return {"pdf": f"/patent/{run_id}.pdf", **evidence.patent_text(run)}


@app.get("/patent/{run_id}.pdf")
async def get_patent_pdf(run_id: str):
    path = evidence.REPORTS / f"{run_id}_patent_draft.pdf"
    if not path.exists():
        raise HTTPException(404, "patent draft not generated yet")
    return FileResponse(path, media_type="application/pdf")


# ---------------------------------------------------------------- Read APIs + static

@app.get("/runs")
async def get_runs():
    return db.list_runs()


@app.get("/runs/{run_id}")
async def get_run(run_id: str):
    run = load_run(run_id)
    return {**run, "events": db.list_events(run_id), "chain_ok": db.verify_chain(run_id),
            "protocol": wf.PROTOCOL}


@app.get("/protocol")
async def get_protocol():
    return wf.PROTOCOL


@app.get("/health")
async def health():
    return {"ok": True, "clients": list(hub.clients.values())}


# Built web app (incl. /structures/*.pdb and /xr.html); `npm run build` in web/ first.
WEB_DIST = ROOT / "web" / "dist"
if WEB_DIST.exists():
    app.mount("/", StaticFiles(directory=WEB_DIST, html=True), name="web")

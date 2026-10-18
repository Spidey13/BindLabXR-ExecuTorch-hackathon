// WebXR viewer for the Galaxy XR browser: the binding complex floats in passthrough,
// the binder docks in when the run reports a successful binding, and pinch/trigger
// grabs the molecule so it can be turned and walked around.
import * as THREE from 'three'
import { ARButton } from 'three/examples/jsm/webxr/ARButton.js'
import { VRButton } from 'three/examples/jsm/webxr/VRButton.js'
import { API, WS_URL, type Run } from './api'

const TARGET = 'A', BINDER = 'D'
const SCALE = 0.012 // metres per Å: ~50 Å complex -> ~0.6 m
const DOCK_OFFSET = 28 // Å
const status = document.getElementById('status')!

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
renderer.setPixelRatio(devicePixelRatio)
renderer.setSize(innerWidth, innerHeight)
renderer.xr.enabled = true
document.body.appendChild(renderer.domElement)

const scene = new THREE.Scene()
const camera = new THREE.PerspectiveCamera(60, innerWidth / innerHeight, 0.01, 50)
camera.position.set(0, 1.4, 0.4)
scene.add(new THREE.HemisphereLight(0xffffff, 0x334455, 2.2))
const sun = new THREE.DirectionalLight(0xffffff, 1.5)
sun.position.set(1, 3, 2)
scene.add(sun)

const molecule = new THREE.Group()
molecule.position.set(0, 1.4, -0.9)
scene.add(molecule)
const binderGroup = new THREE.Group()
molecule.add(binderGroup)

let dockDir = new THREE.Vector3()
let dockT = 1 // 0 = apart, 1 = bound
let docking = false
let scoreSprite: THREE.Sprite | null = null

interface Atom { chain: string; resi: number; x: number; y: number; z: number; el: string }

function parsePdb(text: string): Atom[] {
  return text.split('\n').filter(l => l.startsWith('ATOM')).map(l => ({
    chain: l[21], resi: +l.slice(22, 26), x: +l.slice(30, 38), y: +l.slice(38, 46), z: +l.slice(46, 54),
    el: l.slice(76, 78).trim() || l[13],
  })).filter(a => a.chain === TARGET || a.chain === BINDER)
}

function buildChain(atoms: Atom[], center: THREE.Vector3, base: number, hot: number, iface: Set<number>) {
  const geo = new THREE.SphereGeometry(1, 10, 8)
  const mat = new THREE.MeshStandardMaterial({ roughness: 0.5, metalness: 0.05 })
  const mesh = new THREE.InstancedMesh(geo, mat, atoms.length)
  const m = new THREE.Matrix4(), c = new THREE.Color()
  atoms.forEach((a, i) => {
    const r = (a.el === 'H' ? 0.6 : 1.1) * SCALE * (iface.has(a.resi) ? 1.25 : 1)
    m.makeScale(r, r, r).setPosition((a.x - center.x) * SCALE, (a.y - center.y) * SCALE, (a.z - center.z) * SCALE)
    mesh.setMatrixAt(i, m)
    mesh.setColorAt(i, c.set(iface.has(a.resi) ? hot : base))
  })
  return mesh
}

function makeLabel(text: string): THREE.Sprite {
  const canvas = document.createElement('canvas')
  canvas.width = 512; canvas.height = 128
  const g = canvas.getContext('2d')!
  g.fillStyle = 'rgba(13,13,13,0.8)'
  g.roundRect(0, 0, 512, 128, 24); g.fill()
  g.fillStyle = '#f0efec'; g.font = '600 44px system-ui'; g.textAlign = 'center'; g.textBaseline = 'middle'
  g.fillText(text, 256, 64)
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(canvas), depthTest: false }))
  sprite.scale.set(0.4, 0.1, 1)
  sprite.position.set(0, 0.42, 0)
  return sprite
}

async function load() {
  const runId = new URLSearchParams(location.search).get('run')
  let run: Run | undefined
  try {
    run = runId ? await (await fetch(`${API}/runs/${runId}`)).json()
      : (await (await fetch(`${API}/runs`)).json())[0]
  } catch { /* backend offline: still show the demo structure */ }

  const src = run?.data.structure ?? '/structures/demo_complex.pdb'
  const atoms = parsePdb(await (await fetch(src)).text())
  const center = new THREE.Vector3()
  atoms.forEach(a => center.add(new THREE.Vector3(a.x, a.y, a.z)))
  center.divideScalar(atoms.length)

  const tIface = new Set(run?.data.target_residues ?? [27, 59, 73, 83, 87, 102])
  const bIface = new Set(run?.data.binder_residues ?? [29, 35, 38, 39, 76])
  const target = atoms.filter(a => a.chain === TARGET), binder = atoms.filter(a => a.chain === BINDER)
  molecule.add(buildChain(target, center, 0x3987e5, 0x7fffd4, tIface))
  binderGroup.add(buildChain(binder, center, 0xd95926, 0xffe066, bIface))

  const centroid = (xs: Atom[]) => xs.reduce((v, a) => v.add(new THREE.Vector3(a.x, a.y, a.z)), new THREE.Vector3()).divideScalar(xs.length)
  dockDir = centroid(binder).sub(centroid(target)).normalize()

  if (run) setRun(run)
  else status.textContent = 'Demo complex (backend offline)'
}

function setRun(run: Run) {
  const ok = run.state !== 'BIND_FAIL' && run.state !== 'BINDING'
  status.textContent = `Run ${run.id} · ESM2-${run.data.model} · score ${run.data.score.toFixed(2)} · ${run.state}`
  if (scoreSprite) molecule.remove(scoreSprite)
  scoreSprite = makeLabel(ok ? `✓ Binding ${run.data.score.toFixed(2)}` : `✕ No binding ${run.data.score.toFixed(2)}`)
  molecule.add(scoreSprite)
  if (ok && !docking && dockT >= 1) { dockT = 0; docking = true }
}

// Live updates: re-dock when a new successful binding arrives.
const ws = new WebSocket(WS_URL.replace('client=web', 'client=xr-viewer'))
ws.onmessage = async e => {
  const m = JSON.parse(e.data)
  if (m.type === 'state' && (m.state === 'BIND_OK' || m.state === 'BIND_FAIL')) {
    setRun(await (await fetch(`${API}/runs/${m.run_id}`)).json())
  }
}

// Grab with pinch (hands) or trigger (controllers).
for (let i = 0; i < 2; i++) {
  const ctrl = renderer.xr.getController(i)
  ctrl.addEventListener('selectstart', () => ctrl.attach(molecule))
  ctrl.addEventListener('selectend', () => scene.attach(molecule))
  scene.add(ctrl)
}

const xrSupported = 'xr' in navigator
if (xrSupported && await navigator.xr!.isSessionSupported('immersive-ar')) {
  document.body.appendChild(ARButton.createButton(renderer, { optionalFeatures: ['local-floor', 'hand-tracking'] }))
} else {
  document.body.appendChild(VRButton.createButton(renderer))
}

addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight
  camera.updateProjectionMatrix()
  renderer.setSize(innerWidth, innerHeight)
})

const clock = new THREE.Clock()
renderer.setAnimationLoop(() => {
  const dt = clock.getDelta()
  if (docking) {
    dockT = Math.min(1, dockT + dt / 1.8)
    if (dockT >= 1) docking = false
  }
  const ease = 1 - Math.pow(1 - dockT, 3)
  binderGroup.position.copy(dockDir).multiplyScalar(DOCK_OFFSET * SCALE * (1 - ease))
  if (molecule.parent === scene) molecule.rotation.y += dt * 0.25
  scoreSprite?.quaternion.copy(camera.quaternion)
  renderer.render(scene, camera)
})

load().catch(e => { status.textContent = `Failed to load: ${e}` })

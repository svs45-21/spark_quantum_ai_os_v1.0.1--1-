import { Canvas, useFrame } from '@react-three/fiber'
import { Line, OrbitControls, Sphere, Html } from '@react-three/drei'
import { useRef, useMemo } from 'react'
import * as THREE from 'three'

const nodes = [
  { id: 'exec', pos: [0, 2, 0], label: 'Executive' },
  { id: 'code', pos: [-3, -1, 1], label: 'Coder' },
  { id: 'res', pos: [3, -1, -1], label: 'Researcher' },
  { id: 'mem', pos: [0, -3, 0], label: 'Memory' },
]

function PulseLine({ start, end }: { start: [number, number, number], end: [number, number, number] }) {
  const lineRef = useRef<any>()
  
  useFrame(({ clock }) => {
    if (lineRef.current) {
      lineRef.current.material.opacity = 0.2 + (Math.sin(clock.getElapsedTime() * 5) * 0.1)
    }
  })

  return (
    <Line ref={lineRef} points={[start, end]} color="#00D4FF" lineWidth={2} transparent opacity={0.3} />
  )
}

function Network() {
  const group = useRef<THREE.Group>(null)

  useFrame(() => {
    if (group.current) {
      group.current.rotation.y += 0.005
    }
  })

  return (
    <group ref={group}>
      {nodes.map((node) => (
        <group key={node.id} position={node.pos as [number, number, number]}>
          <Sphere args={[0.3, 16, 16]}>
            <meshStandardMaterial color="#8A2BE2" emissive="#8A2BE2" emissiveIntensity={2} />
          </Sphere>
          <Html distanceFactor={10} position={[0, 0.5, 0]} center>
            <div className="text-neonCyan text-xs font-mono font-bold tracking-widest pointer-events-none drop-shadow-[0_0_5px_rgba(0,212,255,1)]">
              {node.label}
            </div>
          </Html>
        </group>
      ))}
      <PulseLine start={nodes[0].pos as [number, number, number]} end={nodes[1].pos as [number, number, number]} />
      <PulseLine start={nodes[0].pos as [number, number, number]} end={nodes[2].pos as [number, number, number]} />
      <PulseLine start={nodes[1].pos as [number, number, number]} end={nodes[3].pos as [number, number, number]} />
      <PulseLine start={nodes[2].pos as [number, number, number]} end={nodes[3].pos as [number, number, number]} />
    </group>
  )
}

export function AgentNetworkGraph() {
  return (
    <div className="w-full h-[300px] glass-panel relative mt-6">
      <div className="absolute top-4 left-4 z-10">
        <h3 className="text-sm font-bold tracking-widest text-white/80 uppercase">AI Thinking Visualization</h3>
        <p className="text-xs text-purpleEnergy">AGENT COLLABORATION NETWORK</p>
      </div>
      <Canvas camera={{ position: [0, 0, 8] }}>
        <ambientLight intensity={0.5} />
        <pointLight position={[10, 10, 10]} intensity={1} color="#00D4FF" />
        <OrbitControls enableZoom={false} enablePan={false} />
        <Network />
      </Canvas>
    </div>
  )
}

import { useRef, useMemo } from 'react'
import { Canvas, useFrame } from '@react-three/fiber'
import { OrbitControls, Stars, Line } from '@react-three/drei'
import * as THREE from 'three'

function GalaxyNodes({ count = 100 }) {
  const meshRef = useRef<THREE.InstancedMesh>(null)
  const dummy = useMemo(() => new THREE.Object3D(), [])

  // Generate random positions for memory nodes
  const particles = useMemo(() => {
    const temp = []
    for (let i = 0; i < count; i++) {
      const t = Math.random() * Math.PI * 2
      const p = Math.acos((Math.random() * 2) - 1)
      const r = Math.random() * 10 + 2
      const x = r * Math.sin(p) * Math.cos(t)
      const y = r * Math.sin(p) * Math.sin(t)
      const z = r * Math.cos(p)
      temp.push({ x, y, z, speed: Math.random() * 0.01 })
    }
    return temp
  }, [count])

  // Generate connections between nodes
  const lines = useMemo(() => {
    const temp = []
    for (let i = 0; i < count; i++) {
      for (let j = i + 1; j < count; j++) {
        const p1 = particles[i]
        const p2 = particles[j]
        const distance = Math.sqrt(
          Math.pow(p1.x - p2.x, 2) + 
          Math.pow(p1.y - p2.y, 2) + 
          Math.pow(p1.z - p2.z, 2)
        )
        if (distance < 3) { // Only connect close nodes
          temp.push([
            [p1.x, p1.y, p1.z],
            [p2.x, p2.y, p2.z]
          ])
        }
      }
    }
    return temp
  }, [particles, count])

  useFrame(() => {
    if (meshRef.current) {
      particles.forEach((particle, i) => {
        // Subtle orbital movement
        const angle = particle.speed
        const x = particle.x * Math.cos(angle) - particle.z * Math.sin(angle)
        const z = particle.x * Math.sin(angle) + particle.z * Math.cos(angle)
        particle.x = x
        particle.z = z

        dummy.position.set(particle.x, particle.y, particle.z)
        dummy.updateMatrix()
        meshRef.current!.setMatrixAt(i, dummy.matrix)
      })
      meshRef.current.instanceMatrix.needsUpdate = true
    }
  })

  return (
    <group>
      <instancedMesh ref={meshRef} args={[undefined, undefined, count]}>
        <sphereGeometry args={[0.05, 16, 16]} />
        <meshStandardMaterial color="#8A2BE2" emissive="#8A2BE2" emissiveIntensity={2} />
      </instancedMesh>
      
      {/* Render neural connections */}
      {lines.map((line, idx) => (
        <Line 
          key={idx} 
          points={line as [number, number, number][]} 
          color="#00D4FF" 
          lineWidth={0.5} 
          transparent 
          opacity={0.15} 
        />
      ))}
    </group>
  )
}

export function MemoryGalaxy() {
  return (
    <div className="w-full h-[400px] glass-panel relative overflow-hidden mt-8">
      <div className="absolute top-4 left-4 z-10">
        <h2 className="text-xl font-bold tracking-widest text-white/80 uppercase">Memory Vault</h2>
        <p className="text-sm text-neonCyan">NEURAL PATHWAYS ACTIVE</p>
      </div>
      <Canvas camera={{ position: [0, 5, 15], fov: 60 }}>
        <color attach="background" args={['#020204']} />
        <ambientLight intensity={0.2} />
        <pointLight position={[10, 10, 10]} intensity={1} color="#00D4FF" />
        <Stars radius={50} depth={50} count={2000} factor={4} saturation={0} fade speed={1} />
        <OrbitControls autoRotate autoRotateSpeed={0.5} enablePan={false} />
        <GalaxyNodes count={80} />
      </Canvas>
    </div>
  )
}

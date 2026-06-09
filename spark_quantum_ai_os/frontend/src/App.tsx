import { useState, useEffect } from 'react'
import { motion } from 'framer-motion'
import { Canvas } from '@react-three/fiber'
import { OrbitControls, Sphere, MeshDistortMaterial } from '@react-three/drei'
import { Activity, BrainCircuit, ShieldAlert, Cpu } from 'lucide-react'
import { AgentDashboard } from './features/agents/AgentDashboard'
import { MemoryGalaxy } from './features/memory/MemoryGalaxy'
import { AgentNetworkGraph } from './features/agents/AgentNetworkGraph'
import { SocDashboard } from './features/soc/SocDashboard'
import { RoboticsPanel } from './features/robotics/RoboticsPanel'
import { VoiceVisualizer } from './features/voice/VoiceVisualizer'

function HolographicOrb() {
  return (
    <Canvas className="w-full h-full absolute inset-0 -z-10">
      <ambientLight intensity={0.5} />
      <directionalLight position={[10, 10, 5]} intensity={1.5} color="#00D4FF" />
      <directionalLight position={[-10, -10, -5]} intensity={1} color="#8A2BE2" />
      <OrbitControls enableZoom={false} autoRotate autoRotateSpeed={2.0} />
      <Sphere args={[1, 64, 64]} scale={1.5}>
        <MeshDistortMaterial
          color="#0055FF"
          emissive="#00D4FF"
          emissiveIntensity={0.8}
          clearcoat={1}
          clearcoatRoughness={0.1}
          metalness={0.8}
          roughness={0.2}
          distort={0.4}
          speed={3}
        />
      </Sphere>
    </Canvas>
  )
}

function GlassCard({ title, icon: Icon, value }: { title: string, icon: any, value: string }) {
  return (
    <motion.div
      whileHover={{ scale: 1.05, y: -5 }}
      whileTap={{ scale: 0.95 }}
      className="glass-panel p-6 flex flex-col items-center justify-center gap-4 cursor-pointer relative overflow-hidden group"
    >
      <div className="absolute inset-0 bg-gradient-to-br from-neonCyan/20 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-500" />
      <Icon size={32} className="text-neonCyan group-hover:text-white transition-colors" />
      <h3 className="text-sm uppercase tracking-widest text-gray-400">{title}</h3>
      <p className="text-2xl font-bold text-neon">{value}</p>
    </motion.div>
  )
}

function App() {
  const [time, setTime] = useState(new Date().toLocaleTimeString())

  useEffect(() => {
    const timer = setInterval(() => setTime(new Date().toLocaleTimeString()), 1000)
    return () => clearInterval(timer)
  }, [])

  return (
    <div className="w-screen h-screen relative flex flex-col overflow-y-auto overflow-x-hidden scrollbar-hide bg-transparent">
      {/* Background 3D Orb - Fixed positioned behind scrolling content */}
      <div className="fixed inset-0 pointer-events-none opacity-40">
        <HolographicOrb />
      </div>

      {/* Top Navigation / Status Bar */}
      <motion.header 
        initial={{ opacity: 0, y: -50 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 1, ease: 'easeOut' }}
        className="w-full h-16 flex justify-between items-center px-8 border-b border-glassBorder bg-spaceBlack/80 backdrop-blur-md sticky top-0 z-50 select-none"
        style={{ WebkitAppRegion: 'drag' } as React.CSSProperties}
      >
        <div className="flex items-center gap-4">
          <BrainCircuit className="text-purpleEnergy animate-pulse" />
          <h1 className="text-xl font-bold tracking-widest text-white">SPARK <span className="text-neonCyan">OS</span> <span className="text-xs text-gray-500 ml-2">v1.0.1</span></h1>
        </div>
        <div className="text-gray-400 font-mono tracking-widest" style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}>{time}</div>
      </motion.header>

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col items-center p-12 z-10 w-full max-w-7xl mx-auto space-y-12 pb-32">
        
        {/* Dashboard Panels (Top) */}
        <motion.div 
          initial={{ opacity: 0, y: 50 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 1, delay: 0.2, ease: 'easeOut' }}
          className="w-full grid grid-cols-4 gap-6 pointer-events-auto"
        >
          <GlassCard title="System Health" icon={Activity} value="98.4%" />
          <GlassCard title="Active Agents" icon={Cpu} value="12" />
          <GlassCard title="Memory Nodes" icon={BrainCircuit} value="1.4M" />
          <GlassCard title="Threat Level" icon={ShieldAlert} value="LOW" />
        </motion.div>

        {/* Agents Dashboard */}
        <motion.div
          initial={{ opacity: 0, y: 50 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 1, delay: 0.4, ease: 'easeOut' }}
          className="w-full"
        >
          <AgentDashboard />
        </motion.div>

        {/* 2-Column Grid for Advanced Visualizations */}
        <div className="w-full grid grid-cols-2 gap-6 pointer-events-auto">
          <motion.div
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 1, delay: 0.6, ease: 'easeOut' }}
          >
            <AgentNetworkGraph />
          </motion.div>
          <motion.div
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 1, delay: 0.7, ease: 'easeOut' }}
          >
            <MemoryGalaxy />
          </motion.div>
        </div>

        {/* SOC & Robotics Stub Panels */}
        <div className="w-full grid grid-cols-2 gap-6 pointer-events-auto pb-20">
          <motion.div
            initial={{ opacity: 0, y: 50 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 1, delay: 0.8, ease: 'easeOut' }}
          >
            <SocDashboard />
          </motion.div>
          <motion.div
            initial={{ opacity: 0, y: 50 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 1, delay: 0.9, ease: 'easeOut' }}
          >
            <RoboticsPanel />
          </motion.div>
        </div>
        
      </main>

      {/* Voice Interface Overlay */}
      <VoiceVisualizer />
    </div>
  )
}

export default App

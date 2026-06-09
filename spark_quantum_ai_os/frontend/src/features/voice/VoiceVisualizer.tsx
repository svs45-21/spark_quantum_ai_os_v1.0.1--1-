import { motion } from 'framer-motion'
import { Mic, Waves } from 'lucide-react'

export function VoiceVisualizer() {
  return (
    <div className="absolute bottom-10 left-1/2 -translate-x-1/2 flex flex-col items-center gap-4 z-50 pointer-events-auto">
      {/* Waveform Visualization stub */}
      <div className="flex items-center gap-1 h-8">
        {[...Array(15)].map((_, i) => (
          <motion.div
            key={i}
            className="w-1 bg-neonCyan rounded-full"
            animate={{ height: ['20%', '100%', '20%'] }}
            transition={{
              duration: Math.random() * 0.5 + 0.5,
              repeat: Infinity,
              delay: Math.random() * 0.5,
            }}
          />
        ))}
      </div>
      
      {/* Live Transcription stub */}
      <div className="glass-panel px-6 py-3 flex items-center gap-4 rounded-full">
        <div className="p-2 bg-neonCyan/20 rounded-full">
          <Mic size={18} className="text-neonCyan animate-pulse" />
        </div>
        <p className="text-sm font-mono tracking-wide text-white/90">
          "Spark, analyze the latest cybersecurity threats..."
        </p>
      </div>
    </div>
  )
}

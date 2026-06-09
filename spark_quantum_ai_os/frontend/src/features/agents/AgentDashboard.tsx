import { motion } from 'framer-motion'
import { Brain, Cpu, Database, Network, Search, FileText, Code, Shield } from 'lucide-react'

const agents = [
  { id: 'exec', name: 'Executive', role: 'Orchestrator', status: 'Thinking', icon: Brain, color: 'text-neonCyan' },
  { id: 'desk', name: 'Desktop', role: 'Automation', status: 'Idle', icon: Cpu, color: 'text-electricBlue' },
  { id: 'mem', name: 'Memory', role: 'Storage', status: 'Indexing', icon: Database, color: 'text-purpleEnergy' },
  { id: 'res', name: 'Researcher', role: 'OSINT', status: 'Researching', icon: Search, color: 'text-neonCyan' },
  { id: 'doc', name: 'Document', role: 'Parser', status: 'Idle', icon: FileText, color: 'text-gray-400' },
  { id: 'code', name: 'Coder', role: 'Engineer', status: 'Executing', icon: Code, color: 'text-purpleEnergy' },
  { id: 'soc', name: 'SOC', role: 'Cybersecurity', status: 'Monitoring', icon: Shield, color: 'text-electricBlue' },
  { id: 'net', name: 'Network', role: 'Routing', status: 'Idle', icon: Network, color: 'text-gray-400' },
]

export function AgentDashboard() {
  return (
    <div className="w-full max-w-7xl mt-8">
      <div className="flex justify-between items-center mb-6">
        <h2 className="text-xl font-bold tracking-widest text-white/80 uppercase">Active Agents</h2>
        <div className="text-sm text-neonCyan animate-pulse">SYSTEM NOMINAL</div>
      </div>
      
      <div className="grid grid-cols-4 gap-6">
        {agents.map((agent, i) => (
          <motion.div
            key={agent.id}
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: i * 0.1, duration: 0.5, type: 'spring' }}
            whileHover={{ scale: 1.05, rotateY: 10, rotateX: 5 }}
            className="glass-panel p-6 relative overflow-hidden group cursor-pointer"
            style={{ perspective: 1000 }}
          >
            {/* Glowing Backdrop */}
            <div className={`absolute -inset-1 bg-gradient-to-r from-transparent via-${agent.color.split('-')[1]}/20 to-transparent opacity-0 group-hover:opacity-100 blur-xl transition-all duration-500`} />
            
            <div className="relative z-10 flex flex-col gap-4">
              <div className="flex justify-between items-start">
                <div className={`p-3 rounded-full bg-spaceBlack/50 border border-glassBorder ${agent.color}`}>
                  <agent.icon size={24} />
                </div>
                <div className="flex items-center gap-2">
                  <span className={`w-2 h-2 rounded-full ${agent.status !== 'Idle' ? 'bg-neonCyan animate-ping' : 'bg-gray-500'}`} />
                  <span className="text-xs uppercase tracking-wider text-gray-400">{agent.status}</span>
                </div>
              </div>
              
              <div>
                <h3 className="text-lg font-bold text-white tracking-wide">{agent.name}</h3>
                <p className="text-sm text-gray-400">{agent.role}</p>
              </div>
              
              <div className="w-full h-1 bg-spaceBlack/50 rounded-full overflow-hidden mt-2">
                <motion.div 
                  className={`h-full bg-gradient-to-r ${agent.color.replace('text', 'from')} to-transparent`}
                  initial={{ width: 0 }}
                  animate={{ width: agent.status !== 'Idle' ? '100%' : '10%' }}
                  transition={{ duration: 2, repeat: agent.status !== 'Idle' ? Infinity : 0 }}
                />
              </div>
            </div>
          </motion.div>
        ))}
      </div>
    </div>
  )
}

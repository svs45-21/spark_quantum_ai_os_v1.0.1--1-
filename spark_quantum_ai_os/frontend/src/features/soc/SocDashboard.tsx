import { Shield, Activity, AlertTriangle, Terminal } from 'lucide-react'

export function SocDashboard() {
  return (
    <div className="glass-panel p-6 mt-8 flex flex-col gap-4">
      <div className="flex justify-between items-center border-b border-glassBorder pb-4">
        <div className="flex items-center gap-3">
          <Shield className="text-electricBlue" />
          <h2 className="text-xl font-bold tracking-widest text-white/80 uppercase">Cybersecurity SOC</h2>
        </div>
        <div className="flex items-center gap-2 text-xs text-red-500 animate-pulse">
          <AlertTriangle size={14} /> THREATS DETECTED: 0
        </div>
      </div>
      
      <div className="grid grid-cols-3 gap-4">
        <div className="bg-spaceBlack/50 p-4 rounded-xl border border-glassBorder flex flex-col gap-2">
          <span className="text-xs text-gray-500 tracking-wider">NETWORK TRAFFIC</span>
          <div className="flex items-end gap-2 text-neonCyan font-mono">
            <span className="text-2xl">4.2</span>
            <span className="text-sm mb-1">GB/s</span>
          </div>
        </div>
        <div className="bg-spaceBlack/50 p-4 rounded-xl border border-glassBorder flex flex-col gap-2">
          <span className="text-xs text-gray-500 tracking-wider">ACTIVE CONNECTIONS</span>
          <div className="flex items-end gap-2 text-electricBlue font-mono">
            <span className="text-2xl">1,024</span>
          </div>
        </div>
        <div className="bg-spaceBlack/50 p-4 rounded-xl border border-glassBorder flex flex-col gap-2">
          <span className="text-xs text-gray-500 tracking-wider">SYSTEM LOG</span>
          <div className="flex items-center gap-2 text-gray-300 font-mono text-xs">
            <Terminal size={14} /> [OK] Firewall active
          </div>
        </div>
      </div>
    </div>
  )
}

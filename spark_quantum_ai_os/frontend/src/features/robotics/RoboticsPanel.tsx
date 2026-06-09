import { Bot, Battery, Cpu, Radio } from 'lucide-react'

export function RoboticsPanel() {
  return (
    <div className="glass-panel p-6 mt-8 flex flex-col gap-4">
      <div className="flex justify-between items-center border-b border-glassBorder pb-4">
        <div className="flex items-center gap-3">
          <Bot className="text-purpleEnergy" />
          <h2 className="text-xl font-bold tracking-widest text-white/80 uppercase">Robotics Command</h2>
        </div>
        <div className="flex items-center gap-2 text-xs text-neonCyan">
          <Radio size={14} className="animate-ping" /> LINK ESTABLISHED
        </div>
      </div>
      
      <div className="flex gap-4">
        {/* Robot 1 */}
        <div className="flex-1 bg-spaceBlack/50 p-4 rounded-xl border border-glassBorder">
          <div className="flex justify-between items-center mb-4">
            <span className="font-bold tracking-widest text-sm">UNIT-01 <span className="text-gray-500">(Drone)</span></span>
            <span className="text-xs text-green-400">ONLINE</span>
          </div>
          <div className="space-y-2">
            <div className="flex items-center gap-3 text-xs text-gray-400">
              <Battery size={14} className="text-neonCyan" /> Battery: 88%
            </div>
            <div className="flex items-center gap-3 text-xs text-gray-400">
              <Cpu size={14} className="text-purpleEnergy" /> CPU: 12%
            </div>
          </div>
        </div>

        {/* Robot 2 */}
        <div className="flex-1 bg-spaceBlack/50 p-4 rounded-xl border border-glassBorder opacity-50">
          <div className="flex justify-between items-center mb-4">
            <span className="font-bold tracking-widest text-sm">UNIT-02 <span className="text-gray-500">(Rover)</span></span>
            <span className="text-xs text-red-500">OFFLINE</span>
          </div>
          <div className="space-y-2">
            <div className="flex items-center gap-3 text-xs text-gray-400">
              <Battery size={14} /> Battery: --%
            </div>
            <div className="flex items-center gap-3 text-xs text-gray-400">
              <Cpu size={14} /> CPU: --%
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

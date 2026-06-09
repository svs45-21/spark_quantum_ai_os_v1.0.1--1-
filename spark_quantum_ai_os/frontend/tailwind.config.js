/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        neonCyan: '#00D4FF',
        electricBlue: '#0055FF',
        purpleEnergy: '#8A2BE2',
        spaceBlack: '#05050A',
        glassWhite: 'rgba(255, 255, 255, 0.05)',
        glassBorder: 'rgba(255, 255, 255, 0.1)',
      },
      backgroundImage: {
        'sci-fi-gradient': 'linear-gradient(to right bottom, #05050A, #0a0f1d, #05050A)',
      },
      boxShadow: {
        'neon': '0 0 10px rgba(0, 212, 255, 0.5), 0 0 20px rgba(0, 212, 255, 0.3)',
        'purple-glow': '0 0 15px rgba(138, 43, 226, 0.6)',
      }
    },
  },
  plugins: [],
}

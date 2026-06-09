import { BrowserWindow, app, screen } from "electron";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
//#region electron/main.ts
var __dirname = dirname(fileURLToPath(import.meta.url));
process.env.DIST = join(__dirname, "../dist");
process.env.VITE_PUBLIC = app.isPackaged ? process.env.DIST : join(process.env.DIST, "../public");
var win;
var VITE_DEV_SERVER_URL = process.env["VITE_DEV_SERVER_URL"];
function createWindow() {
	const { width, height } = screen.getPrimaryDisplay().workAreaSize;
	win = new BrowserWindow({
		icon: join(process.env.VITE_PUBLIC, "electron-vite.svg"),
		width,
		height,
		transparent: true,
		frame: false,
		autoHideMenuBar: true,
		webPreferences: { preload: join(__dirname, "preload.mjs") }
	});
	win.webContents.on("did-finish-load", () => {
		win?.webContents.send("main-process-message", (/* @__PURE__ */ new Date()).toLocaleString());
	});
	if (VITE_DEV_SERVER_URL) win.loadURL(VITE_DEV_SERVER_URL);
	else win.loadFile(join(process.env.DIST, "index.html"));
}
app.on("window-all-closed", () => {
	if (process.platform !== "darwin") {
		app.quit();
		win = null;
	}
});
app.on("activate", () => {
	if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
app.whenReady().then(createWindow);
//#endregion
export {};

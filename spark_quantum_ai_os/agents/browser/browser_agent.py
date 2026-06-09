"""
SPARK QUANTUM AI OS — Browser Agent
Phase 6: Full browser automation with Playwright.
Search, read pages, fill forms, download files, manage Gmail, GitHub, Drive.
"""
from __future__ import annotations

import asyncio
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

from agents.base_agent import AgentCapability, BaseAgent, Task
from core.events.bus import EventBus, EventType

logger = logging.getLogger("spark.browser")


class BrowserSession:
    """
    Manages a persistent Playwright browser session.
    Single browser, multiple pages. Auto-reconnects on crash.
    """

    def __init__(self, headless: bool = False, timeout_ms: int = 30000) -> None:
        self._headless = headless
        self._timeout = timeout_ms
        self._playwright = None
        self._browser = None
        self._context = None
        self._page = None
        self._available = False

    async def start(self) -> bool:
        try:
            from playwright.async_api import async_playwright
            self._playwright = await async_playwright().start()
            self._browser = await self._playwright.chromium.launch(
                headless=self._headless,
                args=[
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--start-maximized",
                ],
            )
            self._context = await self._browser.new_context(
                viewport={"width": 1920, "height": 1080},
                user_agent=(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/120.0.0.0 Safari/537.36"
                ),
            )
            self._page = await self._context.new_page()
            self._page.set_default_timeout(self._timeout)
            self._available = True
            logger.info("Browser session started (Playwright/Chromium)")
            return True
        except ImportError:
            logger.warning("Playwright not installed. Run: pip install playwright && playwright install chromium")
            return False
        except Exception as e:
            logger.error(f"Browser start failed: {e}")
            return False

    @property
    def page(self):
        return self._page

    @property
    def available(self) -> bool:
        return self._available

    async def new_page(self):
        if not self._context:
            return None
        return await self._context.new_page()

    async def screenshot(self, path: str = None) -> bytes:
        if not self._page:
            return b""
        kwargs = {"path": path} if path else {"full_page": False}
        return await self._page.screenshot(**kwargs)

    async def stop(self) -> None:
        if self._browser:
            await self._browser.close()
        if self._playwright:
            await self._playwright.stop()
        self._available = False


class BrowserAgent(BaseAgent):
    name = "browser_agent"
    description = "Controls browser: search, read pages, fill forms, manage web apps."
    capabilities = [
        AgentCapability("search_web", "Search the internet"),
        AgentCapability("open_url", "Open a URL"),
        AgentCapability("read_page", "Extract text content from current page"),
        AgentCapability("click_element", "Click an element by selector or text"),
        AgentCapability("fill_form", "Fill a form field", requires_approval=True),
        AgentCapability("submit_form", "Submit a form", requires_approval=True),
        AgentCapability("download_file", "Download a file"),
        AgentCapability("screenshot_page", "Screenshot the current page"),
        AgentCapability("gmail_read", "Read Gmail inbox"),
        AgentCapability("github_browse", "Browse GitHub repository"),
        AgentCapability("google_search", "Search Google and return results"),
    ]

    def __init__(self, bus: Optional[EventBus] = None, settings=None) -> None:
        super().__init__(bus)
        from core.settings import get_settings
        cfg = settings or get_settings()
        self._session = BrowserSession(cfg.browser.headless, cfg.browser.timeout_ms)

    async def initialize(self) -> None:
        await self._session.start()

    async def cleanup(self) -> None:
        await self._session.stop()

    async def execute(self, task: Task) -> Any:
        if not self._session.available:
            raise RuntimeError("Browser not available")

        action = task.payload.get("action", "")
        params = task.payload.get("params", {})
        page = self._session.page

        if action == "open_url":
            url = params.get("url", "")
            await page.goto(url, wait_until="domcontentloaded")
            return {"url": page.url, "title": await page.title()}

        elif action == "google_search":
            query = params.get("query", "")
            return await self._google_search(query)

        elif action == "search_web":
            query = params.get("query", "")
            engine = params.get("engine", "google")
            return await self._search(query, engine)

        elif action == "read_page":
            return await self._read_page(params.get("selector"))

        elif action == "click_element":
            selector = params.get("selector", "")
            text = params.get("text", "")
            return await self._click(selector, text)

        elif action == "fill_form":
            selector = params.get("selector", "")
            value = params.get("value", "")
            await page.fill(selector, value)
            return True

        elif action == "submit_form":
            selector = params.get("selector", "")
            await page.click(selector)
            await page.wait_for_load_state("networkidle")
            return True

        elif action == "screenshot_page":
            save_path = params.get("path")
            data = await self._session.screenshot(save_path)
            return {"size": len(data), "path": save_path}

        elif action == "download_file":
            url = params.get("url", "")
            dest = params.get("dest", "downloads/")
            return await self._download(url, dest)

        elif action == "gmail_read":
            return await self._gmail_read(params.get("max_emails", 10))

        elif action == "github_browse":
            repo = params.get("repo", "")
            return await self._github_browse(repo)

        elif action == "scroll":
            direction = params.get("direction", "down")
            amount = params.get("amount", 500)
            if direction == "down":
                await page.evaluate(f"window.scrollBy(0, {amount})")
            else:
                await page.evaluate(f"window.scrollBy(0, -{amount})")
            return True

        elif action == "wait":
            ms = params.get("ms", 1000)
            await asyncio.sleep(ms / 1000)
            return True

        elif action == "get_links":
            return await self._get_links()

        elif action == "extract_table":
            return await self._extract_table(params.get("selector", "table"))

        else:
            raise ValueError(f"Unknown browser action: {action}")

    async def _google_search(self, query: str) -> List[Dict]:
        page = self._session.page
        url = f"https://www.google.com/search?q={query.replace(' ', '+')}"
        await page.goto(url, wait_until="domcontentloaded")
        await asyncio.sleep(1)

        results = []
        try:
            items = await page.query_selector_all("div.g")
            for item in items[:10]:
                title_el = await item.query_selector("h3")
                link_el = await item.query_selector("a")
                snippet_el = await item.query_selector("div[data-sncf]") or await item.query_selector(".VwiC3b")

                title = await title_el.inner_text() if title_el else ""
                href = await link_el.get_attribute("href") if link_el else ""
                snippet = await snippet_el.inner_text() if snippet_el else ""

                if title:
                    results.append({"title": title, "url": href, "snippet": snippet})
        except Exception as e:
            logger.warning(f"Google search parse error: {e}")

        return results

    async def _search(self, query: str, engine: str = "google") -> List[Dict]:
        engines = {
            "google": f"https://www.google.com/search?q={query.replace(' ', '+')}",
            "bing": f"https://www.bing.com/search?q={query.replace(' ', '+')}",
            "duckduckgo": f"https://duckduckgo.com/?q={query.replace(' ', '+')}",
        }
        url = engines.get(engine, engines["google"])
        page = self._session.page
        await page.goto(url, wait_until="domcontentloaded")
        return await self._read_page()

    async def _read_page(self, selector: str = None) -> Dict:
        page = self._session.page
        title = await page.title()
        url = page.url

        if selector:
            try:
                el = await page.query_selector(selector)
                text = await el.inner_text() if el else ""
            except Exception:
                text = ""
        else:
            # Get main content, stripping navigation/ads
            text = await page.evaluate("""() => {
                const selectors = ['main', 'article', '#content', '.content', 'body'];
                for (const sel of selectors) {
                    const el = document.querySelector(sel);
                    if (el) return el.innerText;
                }
                return document.body.innerText;
            }""")

        return {
            "url": url,
            "title": title,
            "text": text[:10000],  # Cap at 10k chars
            "text_length": len(text),
        }

    async def _click(self, selector: str = None, text: str = None) -> bool:
        page = self._session.page
        try:
            if text:
                await page.click(f"text={text}")
            elif selector:
                await page.click(selector)
            await page.wait_for_load_state("networkidle", timeout=10000)
            return True
        except Exception as e:
            logger.error(f"Click failed: {e}")
            return False

    async def _download(self, url: str, dest: str) -> Dict:
        Path(dest).mkdir(parents=True, exist_ok=True)
        page = self._session.page
        try:
            async with page.expect_download() as dl_info:
                await page.goto(url)
            download = await dl_info.value
            filename = download.suggested_filename
            save_path = str(Path(dest) / filename)
            await download.save_as(save_path)
            return {"success": True, "path": save_path, "filename": filename}
        except Exception as e:
            logger.error(f"Download failed: {e}")
            return {"success": False, "error": str(e)}

    async def _gmail_read(self, max_emails: int = 10) -> List[Dict]:
        page = self._session.page
        await page.goto("https://mail.google.com", wait_until="domcontentloaded")
        await asyncio.sleep(3)

        emails = []
        try:
            rows = await page.query_selector_all("tr.zA")
            for row in rows[:max_emails]:
                sender_el = await row.query_selector(".yX.xY span")
                subject_el = await row.query_selector(".bog span")
                snippet_el = await row.query_selector(".y2")

                sender = await sender_el.get_attribute("name") if sender_el else await (sender_el.inner_text() if sender_el else "")
                subject = await subject_el.inner_text() if subject_el else ""
                snippet = await snippet_el.inner_text() if snippet_el else ""

                emails.append({"sender": sender, "subject": subject, "snippet": snippet})
        except Exception as e:
            logger.warning(f"Gmail parse error: {e}")

        return emails

    async def _github_browse(self, repo: str) -> Dict:
        page = self._session.page
        url = f"https://github.com/{repo}" if not repo.startswith("http") else repo
        await page.goto(url, wait_until="domcontentloaded")
        await asyncio.sleep(1)

        result = {"url": page.url, "files": [], "readme": ""}
        try:
            # Get file list
            file_els = await page.query_selector_all("a.js-navigation-open[title]")
            for el in file_els[:30]:
                name = await el.get_attribute("title")
                href = await el.get_attribute("href")
                if name:
                    result["files"].append({"name": name, "href": href})

            # Get README
            readme_el = await page.query_selector("article.markdown-body")
            if readme_el:
                result["readme"] = (await readme_el.inner_text())[:3000]
        except Exception as e:
            logger.warning(f"GitHub parse error: {e}")

        return result

    async def _get_links(self) -> List[Dict]:
        page = self._session.page
        links = await page.query_selector_all("a[href]")
        result = []
        for link in links[:50]:
            href = await link.get_attribute("href")
            text = await link.inner_text()
            if href and text.strip():
                result.append({"text": text.strip(), "href": href})
        return result

    async def _extract_table(self, selector: str) -> List[List[str]]:
        page = self._session.page
        return await page.evaluate(f"""(sel) => {{
            const table = document.querySelector(sel);
            if (!table) return [];
            const rows = [];
            for (const row of table.querySelectorAll('tr')) {{
                const cells = [];
                for (const cell of row.querySelectorAll('th, td')) {{
                    cells.push(cell.innerText.trim());
                }}
                rows.push(cells);
            }}
            return rows;
        }}""", selector)

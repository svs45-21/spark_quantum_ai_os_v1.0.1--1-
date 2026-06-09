"""
SPARK QUANTUM AI OS — Research Agent
Phase 8: Academic paper search, PDF analysis, literature review generation,
citation generation, technology comparison, knowledge extraction.
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

from agents.base_agent import AgentCapability, BaseAgent, Task
from core.events.bus import EventBus, EventType
from core.router.model_router import ModelRouter, TaskType

logger = logging.getLogger("spark.research")

RESEARCH_DIR = Path("data/research")
RESEARCH_DIR.mkdir(parents=True, exist_ok=True)


class PaperSearcher:
    """
    Search academic papers from multiple sources:
    - Semantic Scholar API (free, no key)
    - arXiv API (free, no key)
    - CrossRef API (free, no key)
    """

    async def semantic_scholar(self, query: str, limit: int = 10) -> List[Dict]:
        import httpx
        try:
            url = "https://api.semanticscholar.org/graph/v1/paper/search"
            params = {
                "query": query,
                "limit": limit,
                "fields": "title,authors,year,abstract,citationCount,openAccessPdf,externalIds",
            }
            async with httpx.AsyncClient(timeout=20) as client:
                r = await client.get(url, params=params)
                r.raise_for_status()
                data = r.json()

            papers = []
            for p in data.get("data", []):
                papers.append({
                    "title": p.get("title", ""),
                    "authors": [a.get("name", "") for a in p.get("authors", [])[:5]],
                    "year": p.get("year"),
                    "abstract": p.get("abstract", "")[:500],
                    "citations": p.get("citationCount", 0),
                    "pdf_url": (p.get("openAccessPdf") or {}).get("url", ""),
                    "doi": (p.get("externalIds") or {}).get("DOI", ""),
                    "source": "semantic_scholar",
                })
            return papers
        except Exception as e:
            logger.error(f"Semantic Scholar search failed: {e}")
            return []

    async def arxiv(self, query: str, limit: int = 10) -> List[Dict]:
        import httpx
        try:
            url = "http://export.arxiv.org/api/query"
            params = {
                "search_query": f"all:{query}",
                "start": 0,
                "max_results": limit,
                "sortBy": "relevance",
            }
            async with httpx.AsyncClient(timeout=20) as client:
                r = await client.get(url, params=params)
                r.raise_for_status()
                content = r.text

            papers = self._parse_arxiv_xml(content)
            return papers
        except Exception as e:
            logger.error(f"arXiv search failed: {e}")
            return []

    def _parse_arxiv_xml(self, xml: str) -> List[Dict]:
        papers = []
        entries = re.findall(r"<entry>(.*?)</entry>", xml, re.DOTALL)
        for entry in entries:
            def tag(t):
                m = re.search(rf"<{t}[^>]*>(.*?)</{t}>", entry, re.DOTALL)
                return m.group(1).strip() if m else ""

            authors = re.findall(r"<name>(.*?)</name>", entry)
            year_match = re.search(r"<published>(\d{4})", entry)

            papers.append({
                "title": tag("title").replace("\n", " "),
                "authors": authors[:5],
                "year": int(year_match.group(1)) if year_match else None,
                "abstract": tag("summary").replace("\n", " ")[:500],
                "pdf_url": re.search(r'href="(https://arxiv.org/pdf/[^"]+)"', entry) and
                           re.search(r'href="(https://arxiv.org/pdf/[^"]+)"', entry).group(1) or "",
                "doi": "",
                "source": "arxiv",
                "citations": 0,
            })
        return papers

    async def crossref(self, query: str, limit: int = 10) -> List[Dict]:
        import httpx
        try:
            url = "https://api.crossref.org/works"
            params = {
                "query": query,
                "rows": limit,
                "select": "title,author,published,abstract,DOI,is-referenced-by-count",
            }
            headers = {"User-Agent": "SPARK-AI-OS/1.0 (mailto:spark@example.com)"}
            async with httpx.AsyncClient(timeout=20, headers=headers) as client:
                r = await client.get(url, params=params)
                r.raise_for_status()
                data = r.json()

            papers = []
            for item in data.get("message", {}).get("items", []):
                title = (item.get("title") or [""])[0]
                authors = [
                    f"{a.get('given', '')} {a.get('family', '')}".strip()
                    for a in item.get("author", [])[:5]
                ]
                year = (item.get("published", {}).get("date-parts") or [[None]])[0][0]
                papers.append({
                    "title": title,
                    "authors": authors,
                    "year": year,
                    "abstract": item.get("abstract", "")[:500],
                    "doi": item.get("DOI", ""),
                    "citations": item.get("is-referenced-by-count", 0),
                    "pdf_url": "",
                    "source": "crossref",
                })
            return papers
        except Exception as e:
            logger.error(f"CrossRef search failed: {e}")
            return []


class CitationGenerator:
    """Generate APA, MLA, IEEE, Chicago citations."""

    def apa(self, paper: Dict) -> str:
        authors = self._format_authors_apa(paper.get("authors", []))
        year = paper.get("year", "n.d.")
        title = paper.get("title", "Unknown title")
        doi = paper.get("doi", "")
        doi_str = f" https://doi.org/{doi}" if doi else ""
        return f"{authors} ({year}). {title}.{doi_str}"

    def ieee(self, paper: Dict, ref_num: int = 1) -> str:
        authors = ", ".join(paper.get("authors", [])[:3])
        if len(paper.get("authors", [])) > 3:
            authors += " et al."
        year = paper.get("year", "n.d.")
        title = paper.get("title", "Unknown title")
        doi = paper.get("doi", "")
        doi_str = f", doi: {doi}" if doi else ""
        return f"[{ref_num}] {authors}, \"{title},\" {year}{doi_str}."

    def mla(self, paper: Dict) -> str:
        authors = paper.get("authors", ["Unknown"])
        first = authors[0].split()
        if len(first) >= 2:
            author_str = f"{first[-1]}, {' '.join(first[:-1])}"
        else:
            author_str = authors[0]
        if len(authors) > 1:
            author_str += f", et al."
        year = paper.get("year", "n.d.")
        title = paper.get("title", "Unknown title")
        return f'{author_str}. "{title}." {year}.'

    def _format_authors_apa(self, authors: List[str]) -> str:
        if not authors:
            return "Unknown Author"
        formatted = []
        for author in authors[:6]:
            parts = author.strip().split()
            if len(parts) >= 2:
                last = parts[-1]
                initials = ". ".join(p[0] for p in parts[:-1]) + "."
                formatted.append(f"{last}, {initials}")
            else:
                formatted.append(author)
        result = ", ".join(formatted[:-1])
        if len(formatted) > 1:
            result += f", & {formatted[-1]}"
        elif formatted:
            result = formatted[0]
        if len(authors) > 6:
            result += ", ... " + formatted[-1]
        return result


class ResearchAgent(BaseAgent):
    name = "research_agent"
    description = "Academic research: paper search, analysis, literature synthesis, citations."
    capabilities = [
        AgentCapability("search_papers", "Search academic papers on a topic"),
        AgentCapability("analyze_topic", "Deep analysis of a research topic"),
        AgentCapability("generate_citations", "Generate citations in APA/IEEE/MLA"),
        AgentCapability("compare_technologies", "Compare technologies or approaches"),
        AgentCapability("summarize_paper", "Summarize an academic paper"),
        AgentCapability("research_report", "Generate a full research report"),
        AgentCapability("find_gaps", "Identify research gaps in a field"),
    ]

    def __init__(
        self,
        router: Optional[ModelRouter] = None,
        bus: Optional[EventBus] = None,
    ) -> None:
        super().__init__(bus)
        self._router = router or ModelRouter()
        self._searcher = PaperSearcher()
        self._citer = CitationGenerator()

    async def execute(self, task: Task) -> Any:
        action = task.payload.get("action", "")
        params = task.payload.get("params", {})

        await self._bus.emit(EventType.RESEARCH_STARTED, {"action": action, "params": params}, source=self.name)

        if action == "search_papers":
            result = await self._search_papers(params)
        elif action == "analyze_topic":
            result = await self._analyze_topic(params)
        elif action == "generate_citations":
            result = await self._generate_citations(params)
        elif action == "compare_technologies":
            result = await self._compare_technologies(params)
        elif action == "summarize_paper":
            result = await self._summarize_paper(params)
        elif action == "research_report":
            result = await self._research_report(params)
        elif action == "find_gaps":
            result = await self._find_gaps(params)
        else:
            raise ValueError(f"Unknown research action: {action}")

        await self._bus.emit(EventType.RESEARCH_COMPLETED, {"action": action}, source=self.name)
        return result

    async def _search_papers(self, params: Dict) -> Dict:
        query = params.get("query", "")
        limit = params.get("limit", 10)
        sources = params.get("sources", ["semantic_scholar", "arxiv"])

        all_papers = []
        tasks = []

        if "semantic_scholar" in sources:
            tasks.append(self._searcher.semantic_scholar(query, limit))
        if "arxiv" in sources:
            tasks.append(self._searcher.arxiv(query, limit))
        if "crossref" in sources:
            tasks.append(self._searcher.crossref(query, limit))

        results = await asyncio.gather(*tasks, return_exceptions=True)
        for r in results:
            if isinstance(r, list):
                all_papers.extend(r)

        # Deduplicate by title
        seen = set()
        unique = []
        for p in all_papers:
            key = p["title"].lower()[:50]
            if key not in seen:
                seen.add(key)
                unique.append(p)

        # Sort by citations
        unique.sort(key=lambda x: x.get("citations", 0), reverse=True)

        # Save to file
        out = RESEARCH_DIR / f"search_{query.replace(' ', '_')[:30]}.json"
        out.write_text(json.dumps(unique, indent=2, ensure_ascii=False), encoding="utf-8")

        return {
            "query": query,
            "count": len(unique),
            "papers": unique[:20],
            "saved_to": str(out),
        }

    async def _analyze_topic(self, params: Dict) -> str:
        topic = params.get("topic", "")
        depth = params.get("depth", "comprehensive")

        # First search for papers
        papers = await self._search_papers({"query": topic, "limit": 15})
        paper_summaries = "\n".join(
            f"- {p['title']} ({p.get('year', 'n.d.')}) — {p.get('abstract', '')[:200]}"
            for p in papers["papers"][:10]
        )

        prompt = f"""Perform a {depth} analysis of the research topic: {topic}

Based on these related papers found:
{paper_summaries}

Provide:
1. Overview of the field
2. Key concepts and definitions
3. Current state of research
4. Major approaches and methodologies
5. Key researchers and institutions
6. Open problems and challenges
7. Future directions

Be detailed and academic."""

        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.RESEARCH,
            max_tokens=4000,
        )

    async def _generate_citations(self, params: Dict) -> Dict:
        papers = params.get("papers", [])
        style = params.get("style", "apa").lower()
        query = params.get("query", "")

        if not papers and query:
            search = await self._search_papers({"query": query, "limit": 10})
            papers = search["papers"]

        citations = []
        for i, p in enumerate(papers, 1):
            if style == "apa":
                citations.append(self._citer.apa(p))
            elif style == "ieee":
                citations.append(self._citer.ieee(p, i))
            elif style == "mla":
                citations.append(self._citer.mla(p))
            else:
                citations.append(self._citer.apa(p))

        return {"style": style, "citations": citations, "count": len(citations)}

    async def _compare_technologies(self, params: Dict) -> str:
        items = params.get("items", [])
        aspect = params.get("aspect", "general")

        if len(items) < 2:
            return "Please provide at least 2 items to compare."

        items_str = ", ".join(items)
        prompt = f"""Create a detailed technical comparison of: {items_str}

Aspect of comparison: {aspect}

Structure:
1. Overview of each technology
2. Comparison Table (features, performance, use cases, pros/cons)
3. Detailed Analysis
4. When to use each
5. Recommendation

Be technical, specific, and objective."""

        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.RESEARCH,
            max_tokens=3000,
        )

    async def _summarize_paper(self, params: Dict) -> str:
        title = params.get("title", "")
        abstract = params.get("abstract", "")
        content = params.get("content", "")

        text = content or abstract or title
        prompt = f"""Summarize this academic paper:

Title: {title}
Content: {text}

Provide:
1. Main contribution (2-3 sentences)
2. Methodology
3. Key findings
4. Limitations
5. Relevance and impact"""

        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.RESEARCH,
            max_tokens=1000,
        )

    async def _research_report(self, params: Dict) -> Dict:
        topic = params.get("topic", "")

        # Search + analyze
        papers = await self._search_papers({"query": topic, "limit": 20, "sources": ["semantic_scholar", "arxiv"]})
        analysis = await self._analyze_topic({"topic": topic})
        citations = await self._generate_citations({"papers": papers["papers"], "style": "apa"})

        # Save combined report
        report = {
            "topic": topic,
            "papers_found": papers["count"],
            "papers": papers["papers"],
            "analysis": analysis,
            "citations": citations["citations"],
            "generated_at": str(asyncio.get_event_loop().time()),
        }

        out = RESEARCH_DIR / f"report_{topic.replace(' ', '_')[:30]}.json"
        out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

        return {
            "topic": topic,
            "papers_found": papers["count"],
            "analysis_length": len(analysis),
            "citations_count": len(citations["citations"]),
            "saved_to": str(out),
            "analysis": analysis,
        }

    async def _find_gaps(self, params: Dict) -> str:
        topic = params.get("topic", "")
        papers = await self._search_papers({"query": topic, "limit": 15})
        summaries = "\n".join(
            f"- {p['title']} ({p.get('year', '')}): {p.get('abstract', '')[:200]}"
            for p in papers["papers"][:10]
        )

        prompt = f"""Based on these papers about {topic}:

{summaries}

Identify:
1. Research gaps — what has NOT been studied
2. Contradictions in existing literature
3. Underexplored methodologies
4. Missing datasets or benchmarks
5. Suggested future research directions with rationale

Be specific and actionable."""

        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.RESEARCH,
            max_tokens=2000,
        )

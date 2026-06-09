"""
SPARK QUANTUM AI OS — Coding Agent
Phase 8: Generate, fix, review, refactor, and execute code.
Supports Python, JS, TypeScript, C, C++, Java, Rust, Go, SQL.
"""
from __future__ import annotations

import asyncio
import logging
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from agents.base_agent import AgentCapability, BaseAgent, Task
from core.events.bus import EventBus, EventType
from core.router.model_router import ModelRouter, TaskType

logger = logging.getLogger("spark.coding")

CODE_DIR = Path("data/code")
CODE_DIR.mkdir(parents=True, exist_ok=True)

LANGUAGE_EXTENSIONS = {
    "python": "py", "javascript": "js", "typescript": "ts",
    "c": "c", "cpp": "cpp", "c++": "cpp", "java": "java",
    "rust": "rs", "go": "go", "sql": "sql", "bash": "sh",
    "html": "html", "css": "css", "markdown": "md",
}

LANGUAGE_RUNNERS = {
    "python": ["python", "{file}"],
    "javascript": ["node", "{file}"],
    "bash": ["bash", "{file}"],
}

CODING_SYSTEM = """You are an expert software engineer. Write production-quality code.

Rules:
- Write complete, working code (no placeholders like # TODO)
- Add docstrings and type hints for Python
- Include error handling
- Follow language best practices (PEP8, ESLint, etc.)
- When returning code, wrap it in ```language ... ``` blocks
- Explain what you built after the code block
"""


class CodeExecutor:
    """Safely execute code in a subprocess with timeout."""

    def __init__(self, timeout: int = 30) -> None:
        self._timeout = timeout

    async def run(self, code: str, language: str, args: List[str] = None) -> Dict:
        runner = LANGUAGE_RUNNERS.get(language.lower())
        if not runner:
            return {"success": False, "error": f"Runner not configured for {language}", "output": ""}

        ext = LANGUAGE_EXTENSIONS.get(language.lower(), "txt")
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self._run_sync, code, ext, runner, args or [])

    def _run_sync(self, code: str, ext: str, runner: List[str], args: List[str]) -> Dict:
        with tempfile.NamedTemporaryFile(suffix=f".{ext}", mode="w", delete=False, encoding="utf-8") as f:
            f.write(code)
            tmp_path = f.name

        try:
            cmd = [c.replace("{file}", tmp_path) for c in runner] + args
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=self._timeout,
                env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
            )
            return {
                "success": result.returncode == 0,
                "returncode": result.returncode,
                "output": result.stdout[:5000],
                "stderr": result.stderr[:2000],
            }
        except subprocess.TimeoutExpired:
            return {"success": False, "error": f"Execution timed out after {self._timeout}s", "output": ""}
        except FileNotFoundError as e:
            return {"success": False, "error": f"Runner not found: {e}", "output": ""}
        finally:
            try:
                os.unlink(tmp_path)
            except Exception:
                pass


class CodingAgent(BaseAgent):
    name = "coding_agent"
    description = "Generate, fix, review, refactor and execute code in any language."
    capabilities = [
        AgentCapability("generate_code", "Generate code from description"),
        AgentCapability("fix_bug", "Fix a bug in provided code"),
        AgentCapability("review_code", "Review code for issues"),
        AgentCapability("refactor_code", "Refactor code for quality"),
        AgentCapability("explain_code", "Explain what code does"),
        AgentCapability("add_tests", "Generate unit tests"),
        AgentCapability("generate_docs", "Generate documentation"),
        AgentCapability("run_code", "Execute code safely", requires_approval=True),
        AgentCapability("create_project", "Scaffold a new project"),
        AgentCapability("convert_code", "Convert code between languages"),
    ]

    def __init__(
        self,
        router: Optional[ModelRouter] = None,
        bus: Optional[EventBus] = None,
    ) -> None:
        super().__init__(bus)
        self._router = router or ModelRouter()
        self._executor = CodeExecutor()

    async def execute(self, task: Task) -> Any:
        action = task.payload.get("action", "")
        params = task.payload.get("params", {})

        if action == "generate_code":
            return await self._generate_code(params)
        elif action == "fix_bug":
            return await self._fix_bug(params)
        elif action == "review_code":
            return await self._review_code(params)
        elif action == "refactor_code":
            return await self._refactor_code(params)
        elif action == "explain_code":
            return await self._explain_code(params)
        elif action == "add_tests":
            return await self._add_tests(params)
        elif action == "generate_docs":
            return await self._generate_docs(params)
        elif action == "run_code":
            return await self._run_code(params)
        elif action == "create_project":
            return await self._create_project(params)
        elif action == "convert_code":
            return await self._convert_code(params)
        else:
            raise ValueError(f"Unknown coding action: {action}")

    async def _llm(self, prompt: str, max_tokens: int = 4000) -> str:
        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.CODING,
            system=CODING_SYSTEM,
            max_tokens=max_tokens,
            temperature=0.2,
        )

    def _extract_code(self, response: str, language: str = "") -> str:
        """Extract code block from LLM response."""
        import re
        # Try language-specific block
        if language:
            m = re.search(rf"```{language}\s*(.*?)```", response, re.DOTALL | re.IGNORECASE)
            if m:
                return m.group(1).strip()
        # Try any code block
        m = re.search(r"```\w*\s*(.*?)```", response, re.DOTALL)
        if m:
            return m.group(1).strip()
        return response.strip()

    def _save_code(self, code: str, language: str, filename: str = None) -> str:
        ext = LANGUAGE_EXTENSIONS.get(language.lower(), "txt")
        name = filename or f"spark_code_{asyncio.get_event_loop().time():.0f}.{ext}"
        path = CODE_DIR / name
        path.write_text(code, encoding="utf-8")
        return str(path)

    async def _generate_code(self, params: Dict) -> Dict:
        description = params.get("description", "")
        language = params.get("language", "python").lower()
        save = params.get("save", True)
        filename = params.get("filename", "")

        prompt = f"""Write {language} code for the following requirement:

{description}

Requirements:
- Complete, working implementation
- Proper error handling
- Clear variable names and comments
- Include a main/example usage section"""

        response = await self._llm(prompt)
        code = self._extract_code(response, language)

        result = {
            "code": code,
            "language": language,
            "description": description,
            "explanation": response.split("```")[-1].strip() if "```" in response else "",
        }

        if save:
            result["saved_to"] = self._save_code(code, language, filename or None)

        return result

    async def _fix_bug(self, params: Dict) -> Dict:
        code = params.get("code", "")
        language = params.get("language", "python")
        error = params.get("error", "")
        description = params.get("description", "")

        prompt = f"""Fix the bug in this {language} code.

Error message: {error}
Problem description: {description}

Code:
```{language}
{code}
```

Provide the COMPLETE fixed code with the bug resolved.
Explain what was wrong and what you fixed."""

        response = await self._llm(prompt)
        fixed_code = self._extract_code(response, language)

        return {
            "original_code": code,
            "fixed_code": fixed_code,
            "language": language,
            "explanation": response.split("```")[-1].strip(),
        }

    async def _review_code(self, params: Dict) -> Dict:
        code = params.get("code", "")
        language = params.get("language", "python")

        prompt = f"""Review this {language} code thoroughly.

```{language}
{code}
```

Provide a structured review covering:
1. **Bugs and Errors** — actual bugs or logic errors
2. **Security Issues** — vulnerabilities or unsafe patterns
3. **Performance** — bottlenecks or inefficiencies
4. **Code Quality** — readability, naming, structure
5. **Best Practices** — language-specific conventions
6. **Missing Features** — error handling, edge cases
7. **Overall Rating** — score out of 10 with justification

Be specific with line numbers or code snippets when pointing out issues."""

        review = await self._llm(prompt, max_tokens=3000)
        return {"code": code, "language": language, "review": review}

    async def _refactor_code(self, params: Dict) -> Dict:
        code = params.get("code", "")
        language = params.get("language", "python")
        goals = params.get("goals", "improve readability, performance, and maintainability")

        prompt = f"""Refactor this {language} code to {goals}.

Original:
```{language}
{code}
```

Provide:
1. Refactored code (complete)
2. Summary of changes made
3. Why each change improves the code"""

        response = await self._llm(prompt)
        refactored = self._extract_code(response, language)

        return {
            "original": code,
            "refactored": refactored,
            "language": language,
            "changes": response.split("```")[-1].strip(),
        }

    async def _explain_code(self, params: Dict) -> str:
        code = params.get("code", "")
        language = params.get("language", "")
        level = params.get("level", "intermediate")

        prompt = f"""Explain this code to a {level}-level programmer.

```{language}
{code}
```

Cover:
1. What the code does (high level)
2. How it works (step by step)
3. Key concepts used
4. Input/Output examples"""

        return await self._llm(prompt, max_tokens=2000)

    async def _add_tests(self, params: Dict) -> Dict:
        code = params.get("code", "")
        language = params.get("language", "python")
        framework = params.get("framework", "pytest" if language == "python" else "")

        prompt = f"""Write comprehensive unit tests for this {language} code using {framework or 'the standard testing framework'}.

Code to test:
```{language}
{code}
```

Include:
- Happy path tests
- Edge cases
- Error cases
- Boundary conditions
- Mock external dependencies if needed"""

        response = await self._llm(prompt)
        test_code = self._extract_code(response, language)
        saved = self._save_code(test_code, language, f"test_{framework}_{int(asyncio.get_event_loop().time())}.{LANGUAGE_EXTENSIONS.get(language, 'txt')}")

        return {"test_code": test_code, "framework": framework, "saved_to": saved}

    async def _generate_docs(self, params: Dict) -> Dict:
        code = params.get("code", "")
        language = params.get("language", "python")
        fmt = params.get("format", "markdown")

        prompt = f"""Generate complete documentation for this {language} code in {fmt} format.

```{language}
{code}
```

Include:
- Overview and purpose
- Installation/requirements
- API reference (all functions/classes)
- Usage examples with code
- Configuration options
- Error handling guide"""

        docs = await self._llm(prompt, max_tokens=3000)
        path = CODE_DIR / f"docs_{int(asyncio.get_event_loop().time())}.md"
        path.write_text(docs, encoding="utf-8")

        return {"documentation": docs, "saved_to": str(path)}

    async def _run_code(self, params: Dict) -> Dict:
        code = params.get("code", "")
        language = params.get("language", "python")
        args = params.get("args", [])

        result = await self._executor.run(code, language, args)
        logger.info(f"Code execution: success={result.get('success')}")
        return result

    async def _create_project(self, params: Dict) -> Dict:
        name = params.get("name", "my_project")
        type_ = params.get("type", "python")
        description = params.get("description", "")

        prompt = f"""Create a complete {type_} project scaffold for: {name}

Description: {description}

Generate the following files as JSON:
{{
  "files": [
    {{"path": "relative/path/to/file.py", "content": "file content here"}},
    ...
  ],
  "instructions": "how to run the project"
}}

Include:
- Main application file
- Configuration files
- Requirements/dependencies
- README.md
- Basic tests
- .gitignore

Return only valid JSON."""

        response = await self._llm(prompt, max_tokens=5000)

        import json, re
        project_dir = CODE_DIR / name
        project_dir.mkdir(parents=True, exist_ok=True)

        created_files = []
        instructions = ""

        try:
            clean = re.search(r"\{.*\}", response, re.DOTALL)
            if clean:
                data = json.loads(clean.group())
                for f in data.get("files", []):
                    file_path = project_dir / f["path"]
                    file_path.parent.mkdir(parents=True, exist_ok=True)
                    file_path.write_text(f["content"], encoding="utf-8")
                    created_files.append(str(file_path))
                instructions = data.get("instructions", "")
        except Exception as e:
            logger.warning(f"Project scaffold parse error: {e}")
            # Fallback: save raw response
            (project_dir / "scaffold.md").write_text(response, encoding="utf-8")
            created_files.append(str(project_dir / "scaffold.md"))

        return {
            "project_dir": str(project_dir),
            "files_created": created_files,
            "instructions": instructions,
        }

    async def _convert_code(self, params: Dict) -> Dict:
        code = params.get("code", "")
        from_lang = params.get("from_language", "python")
        to_lang = params.get("to_language", "javascript")

        prompt = f"""Convert this {from_lang} code to {to_lang}.

Original {from_lang}:
```{from_lang}
{code}
```

Requirements:
- Preserve all functionality
- Use idiomatic {to_lang} patterns
- Keep the same structure where possible
- Add necessary imports/dependencies
- Note any significant differences in behavior"""

        response = await self._llm(prompt)
        converted = self._extract_code(response, to_lang)
        saved = self._save_code(converted, to_lang)

        return {
            "original": code,
            "converted": converted,
            "from_language": from_lang,
            "to_language": to_lang,
            "saved_to": saved,
            "notes": response.split("```")[-1].strip(),
        }

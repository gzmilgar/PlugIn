from dataclasses import dataclass, field


@dataclass
class ABAPStatement:
    text: str
    raw_lines: list = field(default_factory=list)
    start_line: int = 0
    end_line: int = 0


class ABAPParser:

    def parse_file(self, file_path, encoding="utf-8"):
        try:
            with open(file_path, "r", encoding=encoding) as f:
                content = f.read()
        except UnicodeDecodeError:
            with open(file_path, "r", encoding="latin-1") as f:
                content = f.read()
        return self.parse(content)

    def parse(self, source):
        lines = source.split("\n")
        statements = []
        buf = ""
        raw = []
        start = 0

        for i, line in enumerate(lines, 1):
            stripped = line.strip()

            if not stripped or stripped.startswith("*"):
                continue

            clean = self._strip_inline_comment(stripped)
            if not clean:
                continue

            if not buf:
                start = i

            buf = (buf + " " + clean) if buf else clean
            raw.append(line)

            if clean.endswith("."):
                statements.append(ABAPStatement(
                    text=buf[:-1].strip(),
                    raw_lines=list(raw),
                    start_line=start,
                    end_line=i,
                ))
                buf = ""
                raw = []

        if buf:
            statements.append(ABAPStatement(
                text=buf.strip(),
                raw_lines=list(raw),
                start_line=start,
                end_line=len(lines),
            ))

        return statements

    @staticmethod
    def _strip_inline_comment(line):
        in_sq = False
        in_bt = False
        in_tpl = False
        for i, ch in enumerate(line):
            if ch == "'" and not in_bt and not in_tpl:
                in_sq = not in_sq
            elif ch == "`" and not in_sq and not in_tpl:
                in_bt = not in_bt
            elif ch == "|" and not in_sq and not in_bt:
                in_tpl = not in_tpl
            elif ch == '"' and not in_sq and not in_bt and not in_tpl:
                return line[:i].rstrip()
        return line

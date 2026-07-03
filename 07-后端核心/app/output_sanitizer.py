"""Output Sanitizer — 清理LLM输出中的工具幻觉"""
import re

class OutputSanitizer:
    def __init__(self):
        self.tool_pattern = re.compile(r"<tool_call>.*?</tool_call>", re.DOTALL)
        self.xml_pattern = re.compile(r"<function=.*?>.*?</function>", re.DOTALL)
        self.code_pattern = re.compile(r"```.*?```", re.DOTALL)

    def clean(self, text: str) -> str:
        text = self.tool_pattern.sub("", text)
        text = self.xml_pattern.sub("", text)
        text = self.code_pattern.sub("", text)
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text.strip()

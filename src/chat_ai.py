"""
MirrorX Hermes — AI Chat module using OpenRouter API.
Connects to Qwen 2.5 72B Instruct via OpenRouter.
"""

from __future__ import annotations

import json
import logging
import os
import time
from dataclasses import dataclass, field
from typing import List, Optional, Callable
import urllib.request
import urllib.error

log = logging.getLogger("mirrorx.chat")

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
DEFAULT_MODEL = "qwen/qwen-2.5-72b-instruct"


@dataclass
class Message:
    role: str  # "user" or "assistant"
    content: str
    timestamp: float = field(default_factory=time.time)


class ChatAI:
    """AI Chat client using OpenRouter API."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        model: str = DEFAULT_MODEL,
        system_prompt: str = "",
    ):
        self.api_key = api_key or os.environ.get("OPENROUTER_API_KEY", "")
        self.model = model
        self.system_prompt = system_prompt or (
            "You are a helpful AI assistant integrated into MirrorX Hermes, "
            "a remote control application. You can help users with tasks, "
            "answer questions, and provide information. Respond concisely."
        )
        self.history: List[Message] = []
        self.max_history = 20

    def send_message(
        self,
        user_message: str,
        on_chunk: Optional[Callable[[str], None]] = None,
    ) -> str:
        """Send a message and return the response.
        
        Args:
            user_message: The user's message text
            on_chunk: Optional callback for streaming chunks (not used for now)
            
        Returns:
            The AI's response text
        """
        if not self.api_key:
            import webbrowser
            try:
                webbrowser.open("https://openrouter.ai/")
            except Exception as e:
                log.error("Failed to open browser: %s", e)
            return "⚠️ OPENROUTER_API_KEY não configurada. Abrindo https://openrouter.ai/ no navegador para obter uma chave. Use: $env:OPENROUTER_API_KEY='sua_chave'"

        self.history.append(Message(role="user", content=user_message))

        messages = self._build_messages()

        payload = {
            "model": self.model,
            "messages": messages,
            "max_tokens": 1024,
            "temperature": 0.7,
        }

        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://github.com/mirrorx-hermes",
            "X-Title": "MirrorX Hermes",
        }

        try:
            data = json.dumps(payload).encode("utf-8")
            req = urllib.request.Request(OPENROUTER_URL, data=data, headers=headers)

            with urllib.request.urlopen(req, timeout=30) as response:
                result = json.loads(response.read().decode("utf-8"))

            if "choices" in result and result["choices"]:
                assistant_msg = result["choices"][0]["message"]["content"]
                self.history.append(Message(role="assistant", content=assistant_msg))
                self._trim_history()
                return assistant_msg
            else:
                return "❌ Resposta inválida da API"

        except urllib.error.HTTPError as e:
            error_body = ""
            try:
                error_body = e.read().decode("utf-8")
            except Exception:
                pass
            log.error("OpenRouter API error %d: %s", e.code, error_body)
            return f"❌ Erro API ({e.code}): {error_body[:200]}"
        except urllib.error.URLError as e:
            log.error("Network error: %s", e)
            return f"❌ Erro de rede: {e.reason}"
        except Exception as e:
            log.exception("Unexpected error: %s", e)
            return f"❌ Erro inesperado: {str(e)}"

    def _build_messages(self) -> List[dict]:
        """Build the messages array for the API."""
        messages = [{"role": "system", "content": self.system_prompt}]
        for msg in self.history:
            messages.append({"role": msg.role, "content": msg.content})
        return messages

    def _trim_history(self):
        """Keep history within max_history limit."""
        if len(self.history) > self.max_history:
            self.history = self.history[-self.max_history:]

    def clear_history(self):
        """Clear conversation history."""
        self.history.clear()

    def set_model(self, model: str):
        """Change the AI model."""
        self.model = model
        log.info("Chat model changed to: %s", model)

    def set_api_key(self, api_key: str):
        """Update the API key."""
        self.api_key = api_key
        log.info("Chat API key updated")


if __name__ == "__main__":
    import sys
    # Execução interativa para chat via terminal
    print("=" * 60)
    print("             MIRRORX HERMES — CHAT AI (OPENROUTER)            ")
    print("=" * 60)
    
    # Tenta obter a chave do ambiente
    key = os.environ.get("OPENROUTER_API_KEY", "").strip()
    
    if not key:
        print("⚠️  A variável de ambiente OPENROUTER_API_KEY não está configurada.")
        print("Abrindo o site https://openrouter.ai/ para você gerar uma chave...")
        import webbrowser
        try:
            webbrowser.open("https://openrouter.ai/")
        except Exception as e:
            print(f"Erro ao abrir o navegador: {e}")
        
        # Permite que o usuário insira a chave interativamente
        key = input("\nCole sua chave da API OpenRouter aqui (ou Enter para sair): ").strip()
        if not key:
            print("Chave não fornecida. Encerrando o chat.")
            sys.exit(0)
    
    # Inicializa o cliente com a chave obtida
    chat = ChatAI(api_key=key)
    print(f"\n✓ Conectado usando o modelo: {chat.model}")
    print("Digite suas mensagens e pressione Enter. Digite 'sair' para encerrar.")
    print("-" * 60)
    
    while True:
        try:
            user_input = input("\nVocê: ").strip()
            if not user_input:
                continue
            if user_input.lower() in ["sair", "exit", "quit"]:
                print("Encerrando conversa. Até mais!")
                break
                
            print("IA: Pensando...", end="", flush=True)
            response = chat.send_message(user_input)
            
            # Limpa o "Pensando..." da linha
            print("\r" + " " * 15 + "\r", end="", flush=True)
            print(f"IA: {response}")
            
        except KeyboardInterrupt:
            print("\n\nSaindo...")
            break
        except Exception as e:
            print(f"\n❌ Erro: {e}")

"""LLM access through an OpenAI-compatible provider chain (Groq -> Gemini -> Ollama)."""
import json
import logging
import time
from typing import Any, Dict, Iterator, List, Optional, Tuple

import httpx

from . import config

log = logging.getLogger("skillpulse.llm")

# Tests set this to an httpx.MockTransport so no real network call is made.
_transport: Optional[httpx.BaseTransport] = None


class LlmUnavailable(Exception):
    """Raised when no configured provider could produce an answer."""


class StreamInterrupted(Exception):
    """Raised when a provider fails AFTER it already streamed some text (no fallback is possible then)."""


def _client(**kwargs: Any) -> httpx.Client:
    return httpx.Client(transport=_transport, **kwargs)


def strip_fences(text: str) -> str:
    value = text.strip()
    if value.startswith("```"):
        first_newline = value.find("\n")
        last_fence = value.rfind("```")
        if first_newline >= 0 and last_fence > first_newline:
            value = value[first_newline + 1:last_fence].strip()
    return value


def extract_json(text: str) -> Dict[str, Any]:
    cleaned = strip_fences(text)
    try:
        return json.loads(cleaned)
    except ValueError:
        start, end = cleaned.find("{"), cleaned.rfind("}")
        if start >= 0 and end > start:
            return json.loads(cleaned[start:end + 1])
        raise


def _headers(provider: Dict[str, str]) -> Dict[str, str]:
    headers = {"Content-Type": "application/json"}
    if provider["key"]:
        headers["Authorization"] = "Bearer " + provider["key"]
    return headers


def _call(provider: Dict[str, str], messages: List[Dict[str, str]], json_mode: bool,
          temperature: float, max_tokens: int, deadline: float, model: Optional[str] = None) -> str:
    headers = _headers(provider)

    body: Dict[str, Any] = {
        "model": model or provider["model"],
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": False,
    }
    if json_mode:
        body["response_format"] = {"type": "json_object"}

    def budget() -> float:
        """Seconds this single request may still take: the per-provider cap or what is left overall."""
        left = min(config.LLM_TIMEOUT, deadline - time.monotonic())
        if left < 1.0:
            raise TimeoutError("overall LLM deadline reached")
        return left

    with _client() as client:
        response = client.post(provider["url"], headers=headers, json=body, timeout=budget())
        if response.status_code == 400 and json_mode:
            # Some providers reject response_format; retry without it and parse leniently.
            body.pop("response_format", None)
            response = client.post(provider["url"], headers=headers, json=body, timeout=budget())
        response.raise_for_status()
        data = response.json()

    content = data["choices"][0]["message"]["content"]
    if not content:
        raise ValueError("empty response")
    return content


def chat(messages: List[Dict[str, str]], json_mode: bool = False, temperature: float = 0.3,
         max_tokens: int = 3000, fast: bool = False, total_timeout: Optional[float] = None) -> Tuple[str, str]:
    """Returns (text, engineLabel). Tries each provider in order and raises LlmUnavailable if all fail.

    The whole chain shares one deadline (LLM_TOTAL_TIMEOUT_SECONDS, or total_timeout), so three slow providers can
    never add up to more than that. With fast=True each provider's small "fast_model" is used (the chat router).
    """
    providers = config.llm_providers()
    if not providers:
        raise LlmUnavailable("No LLM provider is configured (set GROQ_API_KEY, GEMINI_API_KEY or OLLAMA_ENABLED).")

    deadline = time.monotonic() + (total_timeout if total_timeout is not None else config.LLM_TOTAL_TIMEOUT)
    errors: List[str] = []
    for provider in providers:
        if deadline - time.monotonic() < 1.0:
            errors.append("%s: skipped, overall deadline reached" % provider["name"])
            log.warning("LLM deadline reached before trying %s", provider["name"])
            break
        model = (provider.get("fast_model") or provider["model"]) if fast else provider["model"]
        try:
            text = _call(provider, messages, json_mode, temperature, max_tokens, deadline, model)
            return text, "%s-%s" % (provider["name"], model)
        except Exception as ex:
            log.warning("LLM provider %s failed: %s", provider["name"], ex)
            errors.append("%s: %s" % (provider["name"], ex))
            if fast and model != provider["model"]:
                # The small router model may be renamed or retired; retry once with the provider's main model.
                try:
                    text = _call(provider, messages, json_mode, temperature, max_tokens, deadline, provider["model"])
                    return text, "%s-%s" % (provider["name"], provider["model"])
                except Exception as ex2:
                    log.warning("LLM provider %s main-model retry failed: %s", provider["name"], ex2)
                    errors.append("%s: %s" % (provider["name"], ex2))
    raise LlmUnavailable("; ".join(errors))


# ---- streaming ------------------------------------------------------------------------------------
def delta_from_line(line: str) -> Tuple[bool, str]:
    """Parses one line of an OpenAI-style event stream. Returns (finished, text) where text may be empty."""
    line = line.strip()
    if not line.startswith("data:"):
        return False, ""
    payload = line[5:].strip()
    if payload == "[DONE]":
        return True, ""
    try:
        data = json.loads(payload)
        delta = data["choices"][0].get("delta") or {}
    except (ValueError, KeyError, IndexError, TypeError, AttributeError):
        return False, ""
    text = delta.get("content")
    return False, text if isinstance(text, str) else ""


def _stream_one(provider: Dict[str, str], messages: List[Dict[str, str]], temperature: float, max_tokens: int,
                first_token_deadline: float, stream_deadline: float) -> Iterator[str]:
    """Yields text chunks from one provider. Leaving the generator early closes the HTTP connection."""
    headers = _headers(provider)
    headers["Accept"] = "text/event-stream"
    body = {"model": provider["model"], "messages": messages, "temperature": temperature,
            "max_tokens": max_tokens, "stream": True}

    wait = first_token_deadline - time.monotonic()
    if wait < 1.0:
        raise TimeoutError("overall LLM deadline reached")
    timeout = httpx.Timeout(connect=min(5.0, wait), read=max(1.0, min(config.LLM_TIMEOUT, wait)),
                            write=10.0, pool=5.0)
    with _client(timeout=timeout) as client:
        with client.stream("POST", provider["url"], headers=headers, json=body) as response:
            if response.status_code >= 400:
                response.read()
                raise httpx.HTTPStatusError("HTTP %d" % response.status_code, request=response.request,
                                            response=response)
            for line in response.iter_lines():
                if time.monotonic() > stream_deadline:
                    raise TimeoutError("stream time limit reached")
                finished, text = delta_from_line(line)
                if text:
                    yield text
                if finished:
                    return


def stream_chat(messages: List[Dict[str, str]], temperature: float = 0.4,
                max_tokens: int = 1400) -> Iterator[Tuple[str, str]]:
    """Yields ("engine", label) once, then ("delta", text) chunks.

    Providers are tried in order, but only until the first text arrives: once something has been streamed to the
    learner the next provider cannot continue it, so a later failure raises StreamInterrupted instead.
    Raises LlmUnavailable if no provider produced any text.
    """
    providers = config.llm_providers()
    if not providers:
        raise LlmUnavailable("No LLM provider is configured (set GROQ_API_KEY, GEMINI_API_KEY or OLLAMA_ENABLED).")

    now = time.monotonic()
    first_token_deadline = now + config.LLM_TOTAL_TIMEOUT
    stream_deadline = now + config.STREAM_MAX_SECONDS
    errors: List[str] = []
    for provider in providers:
        if first_token_deadline - time.monotonic() < 1.0:
            errors.append("%s: skipped, overall deadline reached" % provider["name"])
            break
        started = False
        try:
            for chunk in _stream_one(provider, messages, temperature, max_tokens, first_token_deadline,
                                     stream_deadline):
                if not started:
                    started = True
                    yield "engine", "%s-%s" % (provider["name"], provider["model"])
                yield "delta", chunk
            if started:
                return
            errors.append("%s: empty response" % provider["name"])
        except Exception as ex:  # GeneratorExit is not an Exception, so a closed stream is never swallowed here
            log.warning("LLM stream from %s failed: %s", provider["name"], ex)
            if started:
                raise StreamInterrupted("%s: %s" % (provider["name"], ex))
            errors.append("%s: %s" % (provider["name"], ex))
    raise LlmUnavailable("; ".join(errors))

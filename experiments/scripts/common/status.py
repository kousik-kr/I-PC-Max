"""Shared terminal statuses for orchestration records."""
from __future__ import annotations

from enum import Enum


class CompletionStatus(str, Enum):
    SUCCESS = "SUCCESS"
    TIMEOUT = "TIMEOUT"
    OUT_OF_MEMORY = "OUT_OF_MEMORY"
    FUNCTION_HORIZON_EXCEEDED = "FUNCTION_HORIZON_EXCEEDED"
    RESOURCE_LIMIT_EXCEEDED = "RESOURCE_LIMIT_EXCEEDED"
    INVALID_INPUT = "INVALID_INPUT"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    INFRASTRUCTURE_BLOCKED = "INFRASTRUCTURE_BLOCKED"


TERMINAL_STATUSES = frozenset(item.value for item in CompletionStatus)

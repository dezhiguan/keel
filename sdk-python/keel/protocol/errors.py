"""Public error codes generated from the keel/v1 contract."""

from keel._generated.errors import ErrorCode


class KeelError(Exception):
    def __init__(self, code: ErrorCode, message: str | None = None):
        self.code = code
        super().__init__(message or code.message)


__all__ = ["ErrorCode", "KeelError"]

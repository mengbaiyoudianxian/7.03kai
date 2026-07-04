"""AES-256-GCM API Key 加密/解密 — 参考 frellmapi lib/crypto.ts"""
from __future__ import annotations
import os, secrets
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

_KEY_BYTES = 32
_KEY_HEX_LEN = _KEY_BYTES * 2
AUTH_TAG_BYTES = 16  # 锁定GCM标签长度，防截断攻击（参考freellmapi）

_cached_key: bytes | None = None


def init_encryption() -> None:
    """初始化加密密钥。优先级：环境变量 > 自动生成（仅开发环境）"""
    global _cached_key
    env_key = os.environ.get("TP_ENCRYPTION_KEY", "")
    if env_key and len(env_key) == _KEY_HEX_LEN:
        try:
            _cached_key = bytes.fromhex(env_key)
            return
        except ValueError:
            pass
    # 开发环境自动生成
    if os.environ.get("TP_ENV", "") != "production":
        _cached_key = secrets.token_bytes(_KEY_BYTES)
        import logging
        logging.warning("TP_ENCRYPTION_KEY 未设置，使用自动生成的临时密钥（仅开发环境）")
        return
    raise RuntimeError(
        f"TP_ENCRYPTION_KEY 必须设置为 {_KEY_HEX_LEN} 位十六进制密钥。"
        f"生成: python -c \"import secrets; print(secrets.token_hex({_KEY_BYTES}))\""
    )


def _key() -> bytes:
    global _cached_key
    if _cached_key is None:
        init_encryption()
    return _cached_key  # type: ignore[return-value]


def encrypt(plaintext: str) -> tuple[str, str, str]:
    """返回 (encrypted_hex, iv_hex, auth_tag_hex)"""
    iv = secrets.token_bytes(12)
    aesgcm = AESGCM(_key())
    ciphertext = aesgcm.encrypt(iv, plaintext.encode(), None)
    # AESGCM.encrypt 返回 ciphertext + AUTH_TAG_BYTES tag 拼接
    encrypted = ciphertext[:-AUTH_TAG_BYTES]
    tag = ciphertext[-AUTH_TAG_BYTES:]
    return encrypted.hex(), iv.hex(), tag.hex()


def decrypt(encrypted_hex: str, iv_hex: str, tag_hex: str) -> str:
    """解密，认证失败自动抛异常。tag长度锁定防截断攻击"""
    encrypted = bytes.fromhex(encrypted_hex)
    iv = bytes.fromhex(iv_hex)
    tag = bytes.fromhex(tag_hex)
    if len(tag) != AUTH_TAG_BYTES:
        raise ValueError(f"认证标签长度异常: {len(tag)}字节（预期{AUTH_TAG_BYTES}），可能被截断篡改")
    aesgcm = AESGCM(_key())
    return aesgcm.decrypt(iv, encrypted + tag, None).decode()


def mask_key(key: str) -> str:
    """脱敏显示：sk-...XXXX"""
    if len(key) <= 8:
        return "****" + key[-4:]
    return key[:4] + "..." + key[-4:]

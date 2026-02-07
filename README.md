<h1 align="center">RusherChat Server</h1>

<h3 align="center">WebSocket backend for RusherChat</h3>

<p align="center">
  <img src="https://img.shields.io/badge/Protocol-WebSocket-5865F2?style=flat" alt="WebSocket">
  <img src="https://img.shields.io/badge/Transport-WSS%20Recommended-62b47a?style=flat" alt="WSS Recommended">
  <img src="https://img.shields.io/badge/Java-21-orange?style=flat&logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/%F0%9F%A7%84-Approved%20%E2%9C%94%EF%B8%8F-blue?style=flat" alt="🧄 Approved ✔️">
</p>

## Overview

**RusherChat Server** is the standalone WebSocket server that powers the
[RusherChat](https://github.com/GarlicRot/RusherChat) plugin for RusherHack.

It provides:

- **Global chat relay** (cross-version)
- **Online presence tracking**
- **End-to-end encrypted whispers** (server cannot read private messages)

## Features

- Global cross-version chat
- Online user list
- Colored names support
- End-to-end encrypted whispers (E2EE)
- Rate limiting / spam protection
- Safe for public deployment behind TLS (WSS)

> [!NOTE]
> Whisper messages are encrypted on the sender’s client and decrypted only on the recipient’s client.
> The server **never decrypts** whisper contents.

## Related

- **RusherChat Plugin:** https://github.com/GarlicRot/RusherChat

## Issues

Report server bugs and request features here:

- https://github.com/GarlicRot/RusherChatServer/issues

## License

MIT License © GarlicRot

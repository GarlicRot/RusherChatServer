<h1 align="center">
  <img src="assets/rusherchatserver.jpg" width="200"><br>
  RusherChat Server
</h1>

<h3 align="center">WebSocket backend for RusherChat</h3>

<p align="center">
  <img src="https://img.shields.io/badge/Protocol-WebSocket-5865F2?style=flat" alt="WebSocket">
  <img src="https://img.shields.io/badge/Transport-WSS%20via%20Proxy-62b47a?style=flat" alt="WSS via Proxy">
  <img src="https://img.shields.io/badge/Java-21-orange?style=flat&logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/%F0%9F%A7%84-Approved%20%E2%9C%94%EF%B8%8F-blue?style=flat" alt="Garlic Approved">
</p>

## Overview

**RusherChat Server** is the standalone WebSocket backend for the
[RusherChat](https://github.com/GarlicRot/RusherChat) RusherHack plugin.

It relays global chat, tracks online users, and routes end-to-end encrypted whispers without decrypting private messages.

## Features

- Global cross-version chat
- Online user tracking
- End-to-end encrypted whispers
- Rate limiting and spam protection
- Public deployment support behind TLS/WSS

## Configuration

| Variable | Default |
|---|---|
| `RUSHERCHAT_PORT` | `42424` |
| `RUSHERCHAT_MAX_MESSAGE_LENGTH` | `256` |
| `RUSHERCHAT_MIN_INTERVAL_MS` | `1000` |

## Related

<p align="center">
  <a href="https://github.com/GarlicRot/RusherChat">
    <img
      src="https://raw.githubusercontent.com/GarlicRot/Rusher-Plugin-Bot/main/assets/Avatar.png"
      width="100"
      alt="RusherChat Plugin"
    ><br>
    <strong>RusherChat Plugin</strong>
  </a>
</p>

<p align="center">
  Required client-side plugin used to communicate with this server.
</p>

## Issues

Report bugs or request features here:

- [RusherChatServer Issues](https://github.com/GarlicRot/RusherChatServer/issues)

## License

MIT

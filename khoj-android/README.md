# Khoj Local for Android

A native, local-first Android second-brain app inspired by the open-source Khoj project. It does **not** connect to the discontinued Khoj Cloud service and does not bundle the original Khoj Python server.

## v1 capabilities

- Local SQLite memory store
- Local full-text search using SQLite FTS4
- Lightweight 384-dimensional hashed similarity vectors for fuzzy/semantic-like recall without model downloads
- Hybrid ranking: lexical + similarity + recency
- Offline extractive answers grounded in retrieved memories with `[memory:ID]` citations
- Optional OpenAI-compatible chat endpoint for generative RAG
- Manual memory create/edit/delete
- Android Share target for text and files
- Multi-file import through the Android document picker
- Plain-text/Markdown/JSON/XML/HTML/YAML/log/source-code extraction
- Binary attachment preservation in app-private storage
- Markdown export of the local memory library
- Dark native UI with Home, Ask, Search, Library and Settings

## Privacy model

Memories and imported attachments stay in app-private Android storage. No network request is made for search, retrieval, import or offline answers. If an AI endpoint is configured in Settings, only the retrieved evidence required for the current question is sent to that endpoint.

## Current v1 limits

- PDF/DOCX/XLSX/PPTX attachments are preserved but their text is not extracted yet.
- The 384-dimensional local similarity index is a deterministic hashed-vector index, not a neural embedding model.
- Generative AI is optional and remote/local-server based; v1 does not bundle a large on-device LLM.

## Identity

Package: `com.kareem.khojlocal`

Version: `1.0.0-local`

This is an unofficial clean-room reimplementation inspired by Khoj. Khoj itself is licensed AGPL-3.0-or-later: https://github.com/khoj-ai/khoj

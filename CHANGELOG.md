# Changelog

All notable changes to KanCard are listed here, newest first.
Format based on [Keep a Changelog](https://keepachangelog.com/).

## [1.0.0] - not released yet

First release.

### Features
- **Today** screen: overdue, due today, next 7 days and high-priority tasks from all projects in one list.
- Each project opens as a **Board** (Kanban) or a **List** (tasks by column, subtasks inline). Every project has its own default view; an option remembers the last view instead.
- Tasks: create, edit, move (drag and drop), close, reopen, delete; due dates, colours, priority, search, Markdown descriptions (with right-to-left text support).
- Task details as a sheet with **subtasks** and **comments**; quick-add line in every column.
- Works offline: changes are queued and sent when the connection returns.
- Sign in with username and password over `https://`, a self-signed `https://` certificate (pinned by SHA-256 fingerprint) or plain `http://` (after a warning). Credentials are stored encrypted with the Android Keystore.
- Local-only mode, JSON backup and restore, home-screen widget per project.
- Appearance: Catppuccin themes, system colours, AMOLED, custom accent.

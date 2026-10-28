# Changelog

All notable changes to KanCard are listed here, newest first.
Format based on [Keep a Changelog](https://keepachangelog.com/).

## [1.1.0] - not released yet

### Added
- **Everything works offline.** Subtasks, comments, new projects and archiving a project are saved on the phone at once and sent when the connection returns, like tasks. A task you created offline can already get subtasks and comments.
- Subtasks and comments of all open tasks are kept on the phone (loaded together in one request), so they can be read offline and the subtask counts show on the cards.
- **List view: drag a task** to another place or another column (press and hold, then drag). Swipe a task to the right to finish it.
- Today: **By project** view (project, tasks, subtasks as a tree). Subtasks show under each task, with real tick boxes, so they can be done without opening anything.
- Task sheet: **Mark done** and **Move to** (column chips) at the top.
- Backups include subtasks and comments (older backups still restore).
- New icon with depth and a themed (monochrome) version for Android 13+.

### Changed
- A server that answers with an error page or "temporarily unavailable" no longer makes the app give up on a queued change; it tries again later.
- Backups of a server workspace can be written without a connection (they hold what is on the phone).
- Database upgraded to version 4 (existing data is kept).

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

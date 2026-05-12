# Windows Local Stack

This folder holds the local Windows setup for the server stack when you want to run the API and web UI on the same machine.

## What It Does

- Keeps the remote Linux deployment unchanged
- Runs the PHP API locally under WAMP or another Apache + PHP stack
- Hosts the built web app locally in the same Apache site
- Lets you disable the local UI without shutting down the API

## Suggested Layout

- `D:\wamp64\www\adfree-api` for the PHP API entrypoint
- `D:\wamp64\www\adfree-web` for the built web app
- `D:\wamp64\storage` for SQLite, queue files, uploads, output, and artifacts

## UI Disable Toggle

Create this file to disable the local UI:

`D:\wamp64\www\adfree-web\.ui-disabled`

Remove that file to turn the UI back on.

The Apache rules in `apps/web/public/.htaccess` block the whole local UI directory when that file exists, but the API stays live.

## Apache VHost

Use `apps/server/windows/wamp-vhost.example.conf` as the starting point for your WAMP virtual host.

It sets the storage environment variables expected by `apps/server/api/public/index.php` and keeps the API and UI paths separate.

## Sync Script

Use `apps/server/windows/setup-local.ps1` to copy the built web assets and API entrypoint into your WAMP site root.

The script accepts a `-DisableUi` switch so you can leave the API exposed while turning the UI off.

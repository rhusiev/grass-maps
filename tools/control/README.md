# Debug control channel

A debug build exposes the command bus as JSON-RPC 2.0 over a Linux abstract
socket. Agents and the host CLI drive the app the way a user does. A release
build contains no channel code: the app depends on `:control` and `:devtools`
in the debug configuration only.

The channel stays on the device. It is an abstract socket, not a network
listener, and the host reaches it through `adb forward`.

## Security

Two gates run before the channel serves one frame:

1. `SO_PEERCRED` must report a UID this channel admits: the app's own UID, or
   the adb daemon's, because `adb forward` proxies through adbd (root on an
   emulator, shell on a device). A different app UID is closed without a reply.
2. The peer must send the 256-bit bearer token in an `auth` request. A wrong
   token is refused, and three failures close the connection. The token value
   is never logged and its string form is redacted.

The token is generated when the channel starts and written to the app-private
file `files/control-token` with mode 0600. The host reads it with
`adb exec-out run-as nl.r1a.grassmap cat files/control-token`, or from
`--token-file`.

After authentication, a command runs only when its generated registry entry has
`allowlistedForControl`. The command bus then still applies the command's own
policy: required switch, dispatcher, cancellation and undo.

## Methods

| Method | Purpose |
|---|---|
| `auth` | `{token}`; every other method is refused until it succeeds |
| `control.commands.list` | id, doc, plugin, allowlisted, requiresSwitch, dispatcher, without activating a plugin |
| `control.command.invoke` | `{command, params}`; refuses a command that is not allowlisted |
| `control.stream.subscribe` | `{stream}`; returns a subscription id |
| `control.stream.unsubscribe` | `{subscription}` |
| `devtools.screenshot` | returns `{format, source, base64}` for the current activity window |

Stream values arrive as `control.stream.data` notifications with
`{subscription, stream, value}`. Refusals carry a JSON-RPC error whose `data`
has a `kind`. The kinds are `unauthorized`, `not_allowlisted`,
`unknown_command`, `unknown_stream`, `not_subscribed`, `switch_off`,
`plugin_failed`, `routing_pack_absent`, `routing_pack_incomplete` and
`routing_pack_unusable`.

A routing-pack refusal uses code `-32005`. Its message is `routing pack absent`,
`routing pack incomplete` or `routing pack unusable`. A pack failure is never
reported as `-32603`. The channel logs every refusal and every handler exception
on the device. The log line names the command. A refusal is logged at warning
level with the typed error. An escaped exception is logged at error level with
its stack trace.

The framing is newline-delimited JSON, one object per line, UTF-8. A frame
larger than 1 MiB ends the connection.

## Host CLI

```sh
tools/control/grassctl.py commands
tools/control/grassctl.py invoke example.set_value 11
tools/control/grassctl.py subscribe example.value --count 3
tools/control/grassctl.py subscribe example.value --count 2 --invoke example.set_value --params 13
tools/control/grassctl.py screenshot /tmp/opencode/grass.png
```

The script sets the `adb forward` rule and reads the token itself. Options:
`--port`, `--abstract`, `--serial`, `--token-file`, `--no-forward`.

The channel serves one controller connection at a time, so a second client waits
until the first disconnects. `subscribe --invoke` changes state from the same
connection.

## MCP server

`tools/control/mcp_server.py` is a stdio MCP bridge over the same client. Tools:
`grassmaps_list_commands`, `grassmaps_invoke_command`, `grassmaps_screenshot`,
`grassmaps_read_stream`. Environment: `GRASSMAPS_CONTROL_PORT`,
`GRASSMAPS_CONTROL_ABSTRACT`, `GRASSMAPS_CONTROL_SERIAL`,
`GRASSMAPS_CONTROL_TOKEN_FILE`.

## Not built here

The release AI-control switch and the user's allowlist are T-0114. This channel
is debug-only and enables every allowlisted command. Section 22 of the
architecture makes devtools methods regular commands that are allowlisted off
by default; `devtools.screenshot` is a control method for now because
`plugins/dev-tools` does not exist yet.

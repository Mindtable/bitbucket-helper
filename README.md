# High level idea

1. Fetch my open PRs
2. Fetch all comments
3. If author != me then store a comment and raise notification
4. Every hour send ping notification on unack comments
5. Simple web page to ack comments
6. Reply to the same thread by me counts as ack to the comment
7. Draw PR comments grouped by the PR
8. Is it possible to replicate BB UI?

# Technical stack

Probably python + uv + venv
Use atlassian python api instead of handcrafted requests https://atlassian-python-api.readthedocs.io/bitbucket.html

# Notifications

Separate notifications library abstracting real tool. Implementation is just a wrapper around 'terminal-notifier' + 'macos' builtin say command
Probably also can embed link directly to the notification + use bb icon

Is it possible to have multiple notifications in macos tray from terminal notifier?

#!/usr/bin/env bash
set -euo pipefail

b0="aaaf435458ae4e0489686b8f74c390be51b2f155"
common_git_dir="$(git rev-parse --path-format=absolute --git-common-dir)"
primary_root="$(dirname "$common_git_dir")"
worktree_root="${primary_root}/.worktrees"

if ! git -C "$primary_root" check-ignore -q .worktrees/; then
    echo "error: .worktrees/ must be ignored before linked worktrees are created" >&2
    exit 1
fi

if ! git -C "$primary_root" cat-file -e "${b0}^{commit}"; then
    echo "error: required B0 commit ${b0} is unavailable; fetch or import the bundle first" >&2
    exit 1
fi

mkdir -p "$worktree_root"

lanes=(
    "v1-core-persistence:codex/v1-core-persistence"
    "v1-api-transports:codex/v1-api-transports"
    "v1-product-cli:codex/v1-product-cli"
    "v1-notifications-scheduler:codex/v1-notifications-scheduler"
)

for lane in "${lanes[@]}"; do
    name="${lane%%:*}"
    branch="${lane#*:}"
    destination="${worktree_root}/${name}"

    if [[ -e "${destination}/.git" ]]; then
        actual_branch="$(git -C "$destination" symbolic-ref --short HEAD)"
        if [[ "$actual_branch" != "$branch" ]]; then
            echo "error: ${destination} is on ${actual_branch}, expected ${branch}" >&2
            exit 1
        fi
    elif [[ -e "$destination" ]]; then
        echo "error: ${destination} exists but is not a Git worktree" >&2
        exit 1
    elif git -C "$primary_root" show-ref --verify --quiet "refs/heads/${branch}"; then
        git -C "$primary_root" worktree add "$destination" "$branch"
    elif git -C "$primary_root" show-ref --verify --quiet "refs/remotes/origin/${branch}"; then
        git -C "$primary_root" worktree add -b "$branch" "$destination" "origin/${branch}"
    else
        echo "error: branch ${branch} is unavailable; fetch it or import the source bundle" >&2
        exit 1
    fi

    if ! git -C "$primary_root" merge-base --is-ancestor "$b0" "$branch"; then
        echo "error: ${branch} does not contain required B0 ${b0}" >&2
        exit 1
    fi

    head="$(git -C "$destination" rev-parse --short HEAD)"
    echo "ready: ${name} (${branch} at ${head})"
done

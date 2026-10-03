"""Keel command line entry point."""

import argparse


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(prog="keel")
    commands = parser.add_subparsers(dest="command", required=True)
    new = commands.add_parser("new", help="Create an agent project")
    new.add_argument("name")
    new.add_argument("--template", default="hello-agent",
                     choices=("hello-agent", "chat-rag", "tool-agent", "graph-agent",
                              "supervisor", "java-spring"))
    dev = commands.add_parser("dev", help="Run an agent with local audit and approvals")
    dev.add_argument("--port", type=int, default=8000)
    dev.add_argument("--no-trace", action="store_true")
    for name in ("eval", "register", "release", "retire", "gate"):
        commands.add_parser(name)
    args = parser.parse_args(argv)
    try:
        if args.command == "new":
            from keel.cli.new import create_project
            create_project(args.name, template=args.template)
        elif args.command == "dev":
            from keel.cli.dev import run_dev
            run_dev(port=args.port, no_trace=args.no_trace)
        else:
            parser.error(f"{args.command} 尚未实现")
    except (ValueError, FileExistsError, FileNotFoundError) as exc:
        parser.error(str(exc))

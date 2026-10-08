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
    envs = ("dev", "test", "staging", "prod")
    register = commands.add_parser("register", help="Register agent.yaml with keel-server")
    register.add_argument("--env", required=True, choices=envs)
    register.add_argument("--file", default="agent.yaml")
    evaluation = commands.add_parser("eval", help="Import or run an evaluation set")
    eval_commands = evaluation.add_subparsers(dest="eval_command", required=True)
    eval_import = eval_commands.add_parser("import")
    eval_import.add_argument("--dataset")
    eval_import.add_argument("--file", default="evals/seed.jsonl")
    eval_run = eval_commands.add_parser("run")
    eval_run.add_argument("--dataset")
    eval_run.add_argument("--file", default="evals/seed.jsonl")
    eval_run.add_argument("--endpoint")
    eval_run.add_argument("--scorers", default="evals/scorers.py")
    eval_run.add_argument("--env", default="dev", choices=envs)
    gate = commands.add_parser("gate", help="Score the seed set and report the result")
    gate.add_argument("--env", required=True, choices=envs)
    gate.add_argument("--file", default="evals/seed.jsonl")
    gate.add_argument("--endpoint")
    gate.add_argument("--scorers", default="evals/scorers.py")
    gate.add_argument("--baseline")
    release = commands.add_parser("release", help="Publish an image after a passing gate")
    release.add_argument("--env", required=True, choices=("staging", "prod"))
    release.add_argument("--image", required=True)
    release.add_argument("--gate-run-id")
    release.add_argument("--file", default="agent.yaml")
    commands.add_parser("retire")
    args = parser.parse_args(argv)
    try:
        if args.command == "new":
            from keel.cli.new import create_project
            create_project(args.name, template=args.template)
        elif args.command == "dev":
            from keel.cli.dev import run_dev
            run_dev(port=args.port, no_trace=args.no_trace)
        elif args.command == "register":
            from keel.cli.register import register as register_agent
            register_agent(env=args.env, manifest_file=args.file)
        elif args.command == "eval" and args.eval_command == "import":
            from keel.cli.eval import import_seed
            import_seed(dataset=args.dataset, seed_file=args.file)
        elif args.command == "eval":
            from keel.cli.eval import run_eval
            run_eval(dataset=args.dataset, seed_file=args.file, scorers_file=args.scorers, endpoint=args.endpoint, env=args.env)
        elif args.command == "gate":
            from keel.cli.gate import gate as run_gate_command
            run_gate_command(env=args.env, seed_file=args.file, scorers_file=args.scorers, endpoint=args.endpoint, baseline_file=args.baseline)
        elif args.command == "release":
            from keel.cli.release import release as release_agent
            release_agent(env=args.env, image=args.image, gate_run_id=args.gate_run_id, manifest_file=args.file)
        else:
            parser.error(f"{args.command} 尚未实现")
    except (ValueError, FileExistsError, FileNotFoundError) as exc:
        parser.error(str(exc))

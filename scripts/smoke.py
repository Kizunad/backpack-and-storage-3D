#!/usr/bin/env python3
"""在隔离目录运行真实 Fabric 四阶段验收；Linux 默认使用 Xvfb，避免自动输入干扰桌面。"""
import argparse
import os
from pathlib import Path
import signal
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
TIMEOUT_SECONDS = 300
SERVER_POLL_SECONDS = 0.5
SERVER_STOP_SECONDS = 30
PROCESS_TERMINATE_SECONDS = 10
PHASE_REVISIONS = {"first": "revision=6", "second": "revision=16",
                   "ground": "revision=19", "ground_restart": "revision=20"}


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--framework-dir", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--show-window", action="store_true", help="显式显示自动验收窗口（会自动操作）")
    args = parser.parse_args()
    if os.name == "nt" and not args.show_window:
        parser.error("Windows 自动验收需显式指定 --show-window；手动体验不要运行 smoke。")
    return args


def main():
    args = parse_args()
    run_id = uuid.uuid4().hex[:12]
    run = ROOT / "build/smoke" / run_id
    prepare_run(run)
    command = [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")), "--console=plain",
               f"-PsmokeRun={run_id}", f"-PframeworkDir={args.framework_dir.resolve()}",
               f"-Dorg.gradle.java.home={args.java_home.resolve()}"]
    if args.offline:
        command.append("--offline")
    subprocess.run(command + [":server:compileSmokeJava", ":client:compileSmokeJava"], cwd=ROOT, check=True)
    print(f"RUN={run}", flush=True)
    for phase, revision in PHASE_REVISIONS.items():
        run_phase(run, command, phase, revision, args.show_window)
    (run.parent / "latest.txt").write_text(str(run))
    print(f"四阶段 3D 验收通过：{run}", flush=True)


def prepare_run(run):
    # 四阶段共用这份隔离世界与玩家文件，分别验证个人库存与地面背包的重启恢复。
    server_dir = run / "server"
    client_dir = run / "client"
    server_dir.mkdir(parents=True)
    client_dir.mkdir(parents=True)
    (server_dir / "eula.txt").write_text("eula=true\n")
    (server_dir / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25579\nonline-mode=false\n"
        "view-distance=2\nsimulation-distance=2\nmax-players=1\nspawn-protection=0\n"
        "level-type=minecraft:flat\ngenerate-structures=false\ndifficulty=peaceful\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},'
        '{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structures":{}}\n'
    )
    (client_dir / "options.txt").write_text("guiScale:2\nlang:zh_cn\nrenderDistance:2\nmaxFps:60\n")


def wait_for_server(server, log_path):
    deadline = time.monotonic() + TIMEOUT_SECONDS
    while "Done (" not in log_path.read_text(errors="replace"):
        if server.poll() is not None or time.monotonic() > deadline:
            raise RuntimeError(f"服务器未就绪：{log_path}")
        time.sleep(SERVER_POLL_SECONDS)


def stop_fixture(server, server_dir):
    # 只结束本轮夹具记录的 JVM，不扫描用户的其他游戏实例。
    if server.poll() is not None:
        return
    pid = server_dir / "fixture.pid"
    if pid.is_file():
        try:
            os.kill(int(pid.read_text()), signal.SIGTERM)
        except ProcessLookupError:
            pass
    server.terminate()
    try:
        server.wait(timeout=PROCESS_TERMINATE_SECONDS)
    except subprocess.TimeoutExpired:
        server.kill()
        server.wait()


def run_phase(run, command, phase, expected_revision, show_window):
    server_dir = run / "server"
    client_dir = run / "client"
    server_log_path = run / f"{phase}-server.log"
    (client_dir / "phase.txt").write_text(phase)
    client_command = command + [":client:runSmoke"]
    if os.name != "nt" and not show_window:
        client_command = ["xvfb-run", "-a", "-s", "-screen 0 1440x960x24"] + client_command

    # 服务器就绪后才启动客户端；退出时无论断言成功与否，都回收本次夹具。
    with server_log_path.open("w") as server_log, (run / f"{phase}-client.log").open("w") as client_log:
        server = subprocess.Popen(
            command + [":server:runSmoke"], cwd=ROOT,
            stdout=server_log, stderr=subprocess.STDOUT,
        )
        try:
            wait_for_server(server, server_log_path)
            subprocess.run(
                client_command, cwd=ROOT, stdout=client_log, stderr=subprocess.STDOUT,
                timeout=TIMEOUT_SECONDS, check=True,
            )
            server.wait(timeout=SERVER_STOP_SECONDS)
            marker = client_dir / f"{phase}-ok.txt"
            if server.returncode != 0 or not marker.is_file():
                raise RuntimeError(f"验收未通过：{run}")
            if (server_dir / "server-result.txt").read_text() != expected_revision:
                raise RuntimeError(f"关闭或断线后仍发生意外提交：{run}")
            print(marker.read_text(), flush=True)
        finally:
            stop_fixture(server, server_dir)


if __name__ == "__main__":
    main()

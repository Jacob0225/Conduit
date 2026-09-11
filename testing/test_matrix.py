#!/usr/bin/env python3
"""Download, install, build, and smoke-test Conduit's dedicated-server matrix."""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


TESTING_DIR = Path(__file__).resolve().parent
ROOT = TESTING_DIR.parent
CACHE_DIR = TESTING_DIR / "cache"
INSTANCE_DIR = TESTING_DIR / "instances"
LOG_DIR = TESTING_DIR / "logs"
MATRIX_FILE = TESTING_DIR / "servers.json"
USER_AGENT = "Conduit-Test-Matrix/1.1.0"
BUILD_LOCKS_GUARD = threading.Lock()
BUILD_LOCKS: dict[Path, threading.Lock] = {}
BUILT_JARS: dict[Path, Path] = {}


@dataclass(frozen=True)
class Target:
    id: str
    project: Path
    loader: str
    minecraft: str
    loader_version: str
    fabric_api_version: str | None
    java: int
    build_java: int
    minecraft_port: int
    conduit_port: int

    @property
    def instance(self) -> Path:
        return INSTANCE_DIR / self.id


def load_targets() -> list[Target]:
    raw = json.loads(MATRIX_FILE.read_text(encoding="utf-8"))
    targets: list[Target] = []
    ids: set[str] = set()
    ports: set[int] = set()
    for item in raw:
        target = Target(
            id=item["id"],
            project=ROOT / item["project"],
            loader=item["loader"].lower(),
            minecraft=item["minecraft"],
            loader_version=item["loaderVersion"],
            fabric_api_version=item.get("fabricApiVersion"),
            java=int(item["java"]),
            build_java=int(item.get("buildJava", item["java"])),
            minecraft_port=int(item["minecraftPort"]),
            conduit_port=int(item["conduitPort"]),
        )
        if target.id in ids:
            raise ValueError(f"Duplicate target id: {target.id}")
        if target.loader not in {"fabric", "forge", "neoforge"}:
            raise ValueError(f"Unsupported loader for {target.id}: {target.loader}")
        if not target.project.is_dir():
            raise ValueError(f"Project directory does not exist: {target.project}")
        for port in (target.minecraft_port, target.conduit_port):
            if not 1 <= port <= 65535:
                raise ValueError(f"Invalid port {port} for {target.id}")
            if port in ports:
                raise ValueError(f"Port {port} is used more than once in servers.json")
            ports.add(port)
        ids.add(target.id)
        targets.append(target)
    return targets


def request_bytes(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, destination: Path, expected_sha1: str | None = None) -> Path:
    if destination.is_file() and destination.stat().st_size > 0:
        if expected_sha1 is None or sha1(destination) == expected_sha1.lower():
            return destination
        destination.unlink()
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    print(f"  downloading {destination.name}")
    try:
        partial.write_bytes(request_bytes(url))
        if expected_sha1 is not None and sha1(partial) != expected_sha1.lower():
            raise RuntimeError(f"SHA-1 mismatch while downloading {destination.name}")
        partial.replace(destination)
    finally:
        partial.unlink(missing_ok=True)
    return destination


def java_major(executable: Path) -> int | None:
    try:
        result = subprocess.run(
            [str(executable), "-version"], capture_output=True, text=True, timeout=15, check=False
        )
    except (OSError, subprocess.SubprocessError):
        return None
    match = re.search(r'version\s+"(?:1\.)?(\d+)', result.stderr + result.stdout)
    return int(match.group(1)) if match else None


def java_candidates(major: int) -> list[Path]:
    keys = (f"JAVA_{major}_HOME", f"JAVA{major}_HOME")
    candidates: list[Path] = []
    for key in keys:
        if os.environ.get(key):
            candidates.append(Path(os.environ[key]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    if os.environ.get("JAVA_HOME"):
        candidates.append(
            Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        )
    system_java = shutil.which("java")
    if system_java:
        candidates.append(Path(system_java))

    # Discover the usual system-managed JDK locations so users do not need to
    # export JAVA_17_HOME/JAVA_21_HOME when those JDKs are already installed.
    if os.name == "nt":
        for variable in ("ProgramFiles", "ProgramFiles(x86)"):
            root = os.environ.get(variable)
            if not root:
                continue
            for pattern in ("Java/*/bin/java.exe", "Eclipse Adoptium/*/bin/java.exe"):
                candidates.extend(Path(root).glob(pattern))
    else:
        candidates.extend(Path("/usr/lib/jvm").glob("*/bin/java"))
        candidates.extend(Path("/Library/Java/JavaVirtualMachines").glob("*/Contents/Home/bin/java"))

    # Preserve priority while avoiding repeated version checks.
    return list(dict.fromkeys(candidates))


def java_executable(target: Target, major: int | None = None) -> str:
    required = target.java if major is None else major
    keys = (f"JAVA_{required}_HOME", f"JAVA{required}_HOME")
    candidates = java_candidates(required)
    for candidate in candidates:
        if candidate.is_file() and java_major(candidate) == required:
            return str(candidate)
    raise RuntimeError(
        f"{target.id} requires Java {required}, but it was not found. "
        f"Install that JDK or set {keys[0]} to its directory."
    )


def gradle_wrapper(project: Path) -> list[str]:
    wrapper = project / "gradlew"
    if os.name == "nt":
        batch = project / "gradlew.bat"
        if batch.is_file():
            return [str(batch)]
        shell = shutil.which("bash") or shutil.which("sh")
        if shell:
            return [shell, str(wrapper)]
        raise RuntimeError("This repository has no gradlew.bat; run the matrix from WSL or install Bash.")
    return [str(wrapper)]


def build_conduit(target: Target, java: str) -> Path:
    project = target.project.resolve()
    with BUILD_LOCKS_GUARD:
        project_lock = BUILD_LOCKS.setdefault(project, threading.Lock())
    with project_lock:
        cached = BUILT_JARS.get(project)
        if cached is not None and cached.is_file():
            print(f"[{target.id}] reusing shared Conduit build")
            return cached
        jar = build_conduit_project(target, java)
        BUILT_JARS[project] = jar
        return jar


def build_conduit_project(target: Target, java: str) -> Path:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / f"build-{target.id}.log"
    print(f"[{target.id}] building Conduit")
    environment = os.environ.copy()
    java_home = str(Path(java).resolve().parent.parent)
    environment["JAVA_HOME"] = java_home
    environment["PATH"] = str(Path(java).resolve().parent) + os.pathsep + environment.get("PATH", "")
    with log_path.open("w", encoding="utf-8") as log:
        result = subprocess.run(
            gradle_wrapper(target.project) + ["--no-daemon", "build"],
            cwd=target.project,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=False,
        )
    if result.returncode:
        raise RuntimeError(f"Conduit build failed; see {log_path}")
    jars = [
        path for path in (target.project / "build" / "libs").glob("*.jar")
        if not any(word in path.name.lower() for word in ("sources", "javadoc", "dev", "shadow"))
    ]
    if not jars:
        raise RuntimeError(f"No release jar found in {target.project / 'build/libs'}")
    return max(jars, key=lambda path: path.stat().st_mtime)


def fabric_installer_version() -> str:
    cache = CACHE_DIR / "fabric-installers.json"
    if not cache.is_file() or time.time() - cache.stat().st_mtime > 86400:
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(request_bytes("https://meta.fabricmc.net/v2/versions/installer"))
    installers = json.loads(cache.read_text(encoding="utf-8"))
    for installer in installers:
        if installer.get("stable"):
            return installer["version"]
    if installers:
        return installers[0]["version"]
    raise RuntimeError("Fabric Meta returned no installer versions")


def minecraft_server_jar(minecraft: str) -> Path:
    manifest_cache = CACHE_DIR / "minecraft-version-manifest.json"
    if not manifest_cache.is_file() or time.time() - manifest_cache.stat().st_mtime > 86400:
        manifest_cache.parent.mkdir(parents=True, exist_ok=True)
        manifest_cache.write_bytes(request_bytes(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        ))
    manifest = json.loads(manifest_cache.read_text(encoding="utf-8"))
    version_url = next(
        (entry["url"] for entry in manifest["versions"] if entry["id"] == minecraft), None
    )
    if version_url is None:
        raise RuntimeError(f"Mojang version manifest has no Minecraft {minecraft}")
    version_cache = CACHE_DIR / f"minecraft-{minecraft}.json"
    if not version_cache.is_file():
        version_cache.write_bytes(request_bytes(version_url))
    version = json.loads(version_cache.read_text(encoding="utf-8"))
    server = version.get("downloads", {}).get("server")
    if not server:
        raise RuntimeError(f"Mojang provides no dedicated server jar for Minecraft {minecraft}")
    return download(
        server["url"], CACHE_DIR / f"minecraft-server-{minecraft}.jar", server.get("sha1")
    )


def provision_fabric(target: Target) -> None:
    if not target.fabric_api_version:
        raise RuntimeError(f"{target.id} is missing fabricApiVersion")
    installer = fabric_installer_version()
    segments = [target.minecraft, target.loader_version, installer]
    encoded = [urllib.parse.quote(segment, safe="") for segment in segments]
    server_name = f"fabric-server-{target.minecraft}-{target.loader_version}-{installer}.jar"
    server_url = "https://meta.fabricmc.net/v2/versions/loader/{}/{}/{}/server/jar".format(*encoded)
    server_jar = download(server_url, CACHE_DIR / server_name)
    shutil.copy2(server_jar, target.instance / "fabric-server-launch.jar")
    shutil.copy2(minecraft_server_jar(target.minecraft), target.instance / "server.jar")

    api_version = target.fabric_api_version
    api_segment = urllib.parse.quote(api_version, safe="")
    api_name = f"fabric-api-{api_version}.jar"
    api_url = (
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/"
        f"{api_segment}/{api_name.replace('+', '%2B')}"
    )
    shutil.copy2(download(api_url, CACHE_DIR / api_name), target.instance / "mods" / api_name)


def installer_url(target: Target) -> tuple[str, str]:
    if target.loader == "forge":
        coordinate = f"{target.minecraft}-{target.loader_version}"
        name = f"forge-{coordinate}-installer.jar"
        return (
            f"https://maven.minecraftforge.net/net/minecraftforge/forge/{coordinate}/{name}", name
        )
    name = f"neoforge-{target.loader_version}-installer.jar"
    return (
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
        f"{target.loader_version}/{name}",
        name,
    )


def server_args_file(target: Target) -> Path | None:
    suffix = "win_args.txt" if os.name == "nt" else "unix_args.txt"
    if target.loader == "forge":
        coordinate = f"{target.minecraft}-{target.loader_version}"
        path = target.instance / "libraries" / "net" / "minecraftforge" / "forge" / coordinate / suffix
    else:
        path = target.instance / "libraries" / "net" / "neoforged" / "neoforge" / target.loader_version / suffix
    return path if path.is_file() else None


def provision_forge_family(target: Target, java: str) -> None:
    existing = server_args_file(target)
    if existing:
        return
    url, name = installer_url(target)
    installer = download(url, CACHE_DIR / name)
    log_path = LOG_DIR / f"install-{target.id}.log"
    print(f"[{target.id}] installing {target.loader}")
    with log_path.open("w", encoding="utf-8") as log:
        result = subprocess.run(
            [java, "-jar", str(installer), "--installServer"],
            cwd=target.instance,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=False,
        )
    if result.returncode or not server_args_file(target):
        raise RuntimeError(f"{target.loader} server installation failed; see {log_path}")


def write_configuration(target: Target) -> None:
    target.instance.mkdir(parents=True, exist_ok=True)
    (target.instance / "mods").mkdir(exist_ok=True)
    config = target.instance / "config" / "conduit"
    config.mkdir(parents=True, exist_ok=True)
    (target.instance / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (target.instance / "server.properties").write_text(
        "\n".join(
            [
                f"server-port={target.minecraft_port}",
                "server-ip=127.0.0.1",
                "online-mode=false",
                "level-type=minecraft:flat",
                "generate-structures=false",
                "spawn-protection=0",
                "view-distance=3",
                "simulation-distance=3",
                "max-players=2",
                f"motd=Conduit test {target.id}",
                "enable-status=true",
                "",
            ]
        ),
        encoding="utf-8",
    )
    (config / "conduit-server.properties").write_text(
        "\n".join(
            [
                "enabled=true",
                "bindAddress=127.0.0.1",
                f"httpPort={target.conduit_port}",
                "publicBaseUrl=",
                "workerThreads=2",
                "logRequests=true",
                "diagnosticsEnabled=true",
                "",
            ]
        ),
        encoding="utf-8",
    )
    (config / "manifest.json").write_text(
        json.dumps(
            {
                "schemaVersion": 2,
                "serverName": f"Conduit Test {target.id}",
                "mods": [],
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def install_conduit(target: Target, jar: Path) -> None:
    mods = target.instance / "mods"
    for old in mods.glob("conduit-*.jar"):
        old.unlink()
    shutil.copy2(jar, mods / jar.name)


def provision(target: Target, conduit_jar: Path | None, java: str | None) -> None:
    target.instance.mkdir(parents=True, exist_ok=True)
    (target.instance / "mods").mkdir(exist_ok=True)
    if target.loader == "fabric":
        provision_fabric(target)
    else:
        if java is None:
            raise RuntimeError(f"Java {target.java} is required to install {target.loader}")
        provision_forge_family(target, java)
    write_configuration(target)
    if conduit_jar:
        install_conduit(target, conduit_jar)


def start_command(target: Target, java: str) -> list[str]:
    if target.loader == "fabric":
        return [java, "-Xms256M", "-Xmx768M", "-jar", "fabric-server-launch.jar", "nogui"]
    args_file = server_args_file(target)
    if not args_file:
        raise RuntimeError(f"Missing generated server argument file for {target.id}")
    relative = args_file.relative_to(target.instance)
    return [java, "-Xms256M", "-Xmx768M", f"@{relative}", "nogui"]


def port_available(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        # Match the reusable-listener behavior used by the Java HTTP server so
        # a recently stopped smoke test is not mistaken for an active process.
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def tail(path: Path, lines: int = 30) -> str:
    if not path.is_file():
        return ""
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:])


def wait_until_ready(process: subprocess.Popen[str], log_path: Path, timeout: int) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Server exited with code {process.returncode}:\n{tail(log_path)}")
        text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.is_file() else ""
        if re.search(r'Done \([\d.]+s\)!', text) or "For help, type \"help\"" in text:
            return
        time.sleep(0.5)
    raise RuntimeError(f"Server did not become ready within {timeout}s:\n{tail(log_path)}")


def check_http(target: Target, startup_timeout: int = 15) -> None:
    deadline = time.monotonic() + startup_timeout
    for route in ("/", "/manifest.json"):
        url = f"http://127.0.0.1:{target.conduit_port}{route}"
        while True:
            try:
                body = request_bytes(url)
                break
            except urllib.error.URLError as error:
                if time.monotonic() >= deadline:
                    raise RuntimeError(
                        f"Conduit HTTP endpoint did not become ready within {startup_timeout}s: {error}"
                    ) from error
                time.sleep(0.1)
        if not body:
            raise RuntimeError(f"Empty response from {url}")
        if route == "/manifest.json":
            payload = json.loads(body)
            if payload.get("serverName") != f"Conduit Test {target.id}":
                raise RuntimeError(f"Unexpected manifest from {url}: {payload}")


def encode_varint(value: int) -> bytes:
    output = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        output.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(output)


def read_varint(stream: Any) -> int:
    value = 0
    for position in range(5):
        byte = stream.recv(1)
        if not byte:
            raise RuntimeError("Server closed the status connection")
        value |= (byte[0] & 0x7F) << (position * 7)
        if not byte[0] & 0x80:
            return value
    raise RuntimeError("Invalid VarInt in Minecraft status response")


def read_exact(stream: Any, length: int) -> bytes:
    output = bytearray()
    while len(output) < length:
        block = stream.recv(length - len(output))
        if not block:
            raise RuntimeError("Incomplete Minecraft status response")
        output.extend(block)
    return bytes(output)


def contains_conduit_marker(value: Any) -> bool:
    if isinstance(value, dict):
        if value.get("insertion") == "conduit:server":
            return True
        return any(contains_conduit_marker(child) for child in value.values())
    if isinstance(value, list):
        return any(contains_conduit_marker(child) for child in value)
    return False


def check_minecraft_status(target: Target) -> None:
    host = b"127.0.0.1"
    handshake = (
        encode_varint(0) + encode_varint(0) + encode_varint(len(host)) + host
        + target.minecraft_port.to_bytes(2, "big") + encode_varint(1)
    )
    with socket.create_connection(("127.0.0.1", target.minecraft_port), timeout=10) as stream:
        stream.sendall(encode_varint(len(handshake)) + handshake + b"\x01\x00")
        read_varint(stream)
        if read_varint(stream) != 0:
            raise RuntimeError("Unexpected Minecraft status packet")
        payload = json.loads(read_exact(stream, read_varint(stream)).decode("utf-8"))
    if not contains_conduit_marker(payload.get("description")):
        raise RuntimeError("Minecraft status response is missing the Conduit capability marker")


def smoke(target: Target, timeout: int, java: str) -> None:
    for port in (target.minecraft_port, target.conduit_port):
        if not port_available(port):
            raise RuntimeError(f"Port {port} is already in use")
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / f"server-{target.id}.log"
    print(f"[{target.id}] starting on MC {target.minecraft_port}, Conduit {target.conduit_port}")
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            start_command(target, java),
            cwd=target.instance,
            stdin=subprocess.PIPE,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        try:
            wait_until_ready(process, log_path, timeout)
            check_http(target)
            check_minecraft_status(target)
        finally:
            if process.poll() is None:
                try:
                    assert process.stdin is not None
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.wait(timeout=30)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)


def run_target(target: Target, command: str, skip_build: bool, timeout: int) -> tuple[str, bool, str]:
    try:
        # A Fabric-only download does not execute Java. Forge and NeoForge use
        # their installer, while builds and smoke tests always execute Java.
        needs_java = command != "download" or target.loader != "fabric"
        java = java_executable(target) if needs_java else None
        jar = None if command == "download" else (
            find_existing_jar(target) if skip_build else
            build_conduit(target, java_executable(target, target.build_java))
        )
        provision(target, jar, java)
        if command in {"all", "smoke"}:
            smoke(target, timeout, java)
        return target.id, True, "downloaded" if command == "download" else "passed"
    except Exception as error:
        return target.id, False, str(error)


def find_existing_jar(target: Target) -> Path:
    jars = [
        path for path in (target.project / "build" / "libs").glob("*.jar")
        if not any(word in path.name.lower() for word in ("sources", "javadoc", "dev", "shadow"))
    ]
    if not jars:
        raise RuntimeError(f"No built Conduit jar for {target.id}; omit --skip-build")
    return max(jars, key=lambda path: path.stat().st_mtime)


def print_targets(targets: list[Target]) -> None:
    print(f"{'TARGET':<22} {'LOADER':<10} {'MC':<10} {'JAVA':<6} {'MC PORT':<9} HTTP PORT")
    for target in targets:
        print(
            f"{target.id:<22} {target.loader:<10} {target.minecraft:<10} "
            f"{target.java:<6} {target.minecraft_port:<9} {target.conduit_port}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", default="all", choices=("all", "download", "smoke", "list"))
    parser.add_argument("--target", action="append", help="Run only this target id; repeat as needed")
    parser.add_argument("--jobs", type=int, default=1, help="Targets to process concurrently (default: 1)")
    parser.add_argument("--timeout", type=int, default=240, help="Server startup timeout in seconds")
    parser.add_argument("--skip-build", action="store_true", help="Reuse jars already in build/libs")
    args = parser.parse_args()

    try:
        targets = load_targets()
    except Exception as error:
        print(f"Matrix error: {error}", file=sys.stderr)
        return 2
    if args.target:
        requested = set(args.target)
        targets = [target for target in targets if target.id in requested]
        missing = requested - {target.id for target in targets}
        if missing:
            print(f"Unknown target(s): {', '.join(sorted(missing))}", file=sys.stderr)
            return 2
    if args.command == "list":
        print_targets(targets)
        return 0
    if args.jobs < 1:
        parser.error("--jobs must be at least 1")

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    INSTANCE_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    results: list[tuple[str, bool, str]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as executor:
        futures = [
            executor.submit(run_target, target, args.command, args.skip_build, args.timeout)
            for target in targets
        ]
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            results.append(result)
            print(f"[{'PASS' if result[1] else 'FAIL'}] {result[0]}: {result[2]}")

    print("\nResult summary")
    by_id = {result[0]: result for result in results}
    for target in targets:
        result = by_id[target.id]
        print(f"  {'PASS' if result[1] else 'FAIL':<4}  {target.id:<22} {result[2]}")
    failures = sum(not result[1] for result in results)
    print(f"\n{len(results) - failures} passed, {failures} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

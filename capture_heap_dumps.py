#!/usr/bin/env python3
"""
Script to compile and run Java test programs, capturing heap dumps with jmap.
This script creates realistic test scenarios for HPROF heap dump analysis.

Features:
- Auto-detects Java test files
- Incremental compilation (only recompiles if source changed)
- Compressed heap dumps (gzip format)
- Removes compiled .class files after tests
- Caches metadata to detect source file changes
"""

import os
import sys
import subprocess
import time
import signal
import json
import hashlib
import gzip
from pathlib import Path
from datetime import datetime
import shutil

class JmapHeapDumpCapture:
    def __init__(self, test_programs_dir, output_dir):
        self.test_programs_dir = Path(test_programs_dir)
        self.output_dir = Path(output_dir)
        self.pids = {}  # Maps process names to PIDs
        self.processes = {}  # Maps process names to Process objects
        self.metadata_file = self.test_programs_dir / ".heap_dump_metadata.json"

        # Create output directory
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.test_programs_dir.mkdir(parents=True, exist_ok=True)

    def load_results(self):
        """Load previous run results from results.json."""
        results_file = self.output_dir / "results.json"
        if results_file.exists():
            try:
                with open(results_file, 'r') as f:
                    return json.load(f)
            except Exception:
                pass
        return {'tests': {}}

    def needs_heap_dump(self, name, java_file):
        """Check if a heap dump needs to be (re)generated for a test.

        Returns True if the source file has changed since the last capture
        or if the previously captured dump file no longer exists.
        """
        previous = self.load_results()
        if name not in previous.get('tests', {}):
            return True

        prev_test = previous['tests'][name]

        # Must have succeeded previously
        if prev_test.get('status') not in ('success',):
            return True

        # Source hash must match
        current_hash = self.compute_file_hash(java_file)
        if prev_test.get('source_hash') != current_hash:
            return True

        # Heap dump file must still exist on disk
        prev_dump = prev_test.get('heap_dump')
        if not prev_dump or not Path(prev_dump).exists():
            return True

        return False

    def check_requirements(self):
        """Check if required tools are available."""
        tools = ['javac', 'java', 'jmap']
        missing = []
        for tool in tools:
            if shutil.which(tool) is None:
                missing.append(tool)

        if missing:
            print(f"Error: Missing required tools: {', '.join(missing)}")
            print("Please ensure Java Development Kit (JDK) is installed and in PATH")
            sys.exit(1)

        print("✓ All required tools found: javac, java, jmap")

    def compute_file_hash(self, file_path):
        """Compute SHA256 hash of a file."""
        sha256_hash = hashlib.sha256()
        with open(file_path, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)
        return sha256_hash.hexdigest()

    def load_metadata(self):
        """Load compilation metadata from cache."""
        if self.metadata_file.exists():
            try:
                with open(self.metadata_file, 'r') as f:
                    return json.load(f)
            except:
                pass
        return {'files': {}}

    def save_metadata(self, metadata):
        """Save compilation metadata to cache."""
        with open(self.metadata_file, 'w') as f:
            json.dump(metadata, f, indent=2)

    def needs_compilation(self, java_file):
        """Check if a Java file needs recompilation."""
        metadata = self.load_metadata()
        file_key = java_file.name

        class_name = self.get_java_class_name(java_file)
        class_file = java_file.parent / f"{class_name}.class"
        if not class_file.exists():
            return True

        current_hash = self.compute_file_hash(java_file)

        if file_key not in metadata['files']:
            return True

        previous_hash = metadata['files'][file_key].get('hash')
        return current_hash != previous_hash

    def compile_java_file(self, java_file):
        """Compile a single Java file if it has changed."""
        java_file = Path(java_file)
        if not java_file.exists():
            print(f"Warning: File not found: {java_file}")
            return False

        # Check if recompilation is needed
        if not self.needs_compilation(java_file):
            print(f"Skipping {java_file.name} (already compiled)", end=" ")
            print("✓")
            return True

        try:
            print(f"Compiling {java_file.name}...", end=" ")
            result = subprocess.run(
                ['javac', str(java_file)],
                capture_output=True,
                timeout=30
            )
            if result.returncode != 0:
                print(f"FAILED")
                print(f"  Error: {result.stderr.decode()}")
                return False

            # Update metadata
            metadata = self.load_metadata()
            current_hash = self.compute_file_hash(java_file)
            metadata['files'][java_file.name] = {
                'hash': current_hash,
                'timestamp': datetime.now().isoformat(),
                'compiled': True
            }
            self.save_metadata(metadata)

            print("✓")
            return True
        except Exception as e:
            print(f"FAILED - {e}")
            return False

    def compile_all(self):
        """Compile all Java test files."""
        print("\n=== Compiling Java Test Programs ===")
        java_files = sorted(self.test_programs_dir.glob("*.java"))

        if not java_files:
            print("No Java files found in test_programs directory")
            return False

        success_count = 0
        for java_file in java_files:
            if self.compile_java_file(java_file):
                success_count += 1

        print(f"✓ Compiled {success_count}/{len(java_files)} files successfully")
        return success_count == len(java_files)

    def cleanup_class_files(self):
        """Remove all compiled .class files."""
        print("\n=== Cleaning Up Compiled Classes ===")
        class_files = list(self.test_programs_dir.glob("*.class"))
        inner_classes = list(self.test_programs_dir.glob("*$*.class"))

        total_files = len(class_files) + len(inner_classes)

        if total_files == 0:
            print("No .class files to clean up")
            return

        print(f"Removing {total_files} .class files...", end=" ")
        try:
            for class_file in class_files + inner_classes:
                class_file.unlink()
            print("✓")
        except Exception as e:
            print(f"Warning: {e}")

    def get_java_class_name(self, java_file):
        """Extract the public class name from Java file."""
        with open(java_file, 'r') as f:
            for line in f:
                if 'public class' in line:
                    # Extract class name
                    parts = line.split('public class')[1].strip().split('{')[0].strip()
                    return parts
        return java_file.stem

    def run_program(self, java_file, name, max_wait_secs=10):
        """Start a Java program and return its PID."""
        class_name = self.get_java_class_name(java_file)

        print(f"Starting {name} ({class_name})...", end=" ")
        try:
            # Run with additional JVM flags for better diagnostics
            process = subprocess.Popen(
                [
                    'java',
                    '-cp', str(self.test_programs_dir),
                    '-XX:+UnlockDiagnosticVMOptions',
                    '-XX:+DebugNonSafepoints',
                    class_name
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                preexec_fn=os.setsid  # Create new process group
            )

            self.processes[name] = process
            self.pids[name] = process.pid

            # Wait a bit for the program to start
            time.sleep(max_wait_secs)

            # Check if process is still running
            if process.poll() is None:
                print(f"✓ (PID: {process.pid})")
                return process.pid
            else:
                print("FAILED - Process exited prematurely")
                stdout, stderr = process.communicate()
                print(f"  stdout: {stdout.decode()}")
                print(f"  stderr: {stderr.decode()}")
                return None

        except Exception as e:
            print(f"FAILED - {e}")
            return None

    def capture_heap_dump(self, name, pid):
        """Capture heap dump using jmap and compress it."""
        if pid is None:
            print(f"  Skipping dump for {name} - no valid PID")
            return None

        # Use .hprof.gz for compressed heap dumps
        dump_file_base = self.output_dir / f"{name}_{pid}_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
        dump_file = dump_file_base.with_suffix('.hprof')
        dump_file_gz = dump_file_base.with_suffix('.hprof.gz')

        print(f"Capturing heap dump for {name} (PID: {pid})...", end=" ")
        try:
            # Capture to temporary uncompressed file
            result = subprocess.run(
                ['jmap', '-dump:live,format=b,file=' + str(dump_file), str(pid)],
                capture_output=True,
                timeout=60
            )

            if result.returncode == 0 and dump_file.exists():
                # Compress the heap dump
                uncompressed_size = dump_file.stat().st_size
                with open(dump_file, 'rb') as f_in:
                    with gzip.open(dump_file_gz, 'wb') as f_out:
                        shutil.copyfileobj(f_in, f_out)

                # Remove uncompressed version
                dump_file.unlink()

                compressed_size = dump_file_gz.stat().st_size
                ratio = (1 - compressed_size / uncompressed_size) * 100 if uncompressed_size > 0 else 0
                print(f"✓ ({compressed_size / (1024*1024):.2f} MB, compressed {ratio:.1f}%)")
                return str(dump_file_gz)
            else:
                print("FAILED")
                if result.stderr:
                    print(f"  Error: {result.stderr.decode()}")
                return None

        except Exception as e:
            print(f"FAILED - {e}")
            return None

    def capture_heap_histogram(self, name, pid):
        """Capture heap histogram using jmap."""
        if pid is None:
            return None

        hist_file = self.output_dir / f"{name}_{pid}_histogram.txt"

        print(f"Capturing heap histogram for {name} (PID: {pid})...", end=" ")
        try:
            result = subprocess.run(
                ['jmap', '-histo', str(pid)],
                capture_output=True,
                timeout=30
            )

            if result.returncode == 0:
                with open(hist_file, 'w') as f:
                    f.write(result.stdout.decode())
                print("✓")
                return str(hist_file)
            else:
                print("FAILED")
                return None

        except Exception as e:
            print(f"FAILED - {e}")
            return None

    def cleanup_previous_outputs(self, name):
        """Remove previous heap dumps and histograms for a test name."""
        patterns = [
            f"{name}_*.hprof",
            f"{name}_*.hprof.gz",
            f"{name}_*_histogram.txt",
        ]

        removed = 0
        for pattern in patterns:
            for file_path in self.output_dir.glob(pattern):
                try:
                    file_path.unlink()
                    removed += 1
                except Exception:
                    pass

        if removed > 0:
            print(f"Removed {removed} previous dump file(s) for {name} ✓")

    def terminate_process(self, name):
        """Terminate a running process."""
        if name not in self.pids:
            return

        pid = self.pids[name]
        process = self.processes.get(name)

        print(f"Terminating {name} (PID: {pid})...", end=" ")
        try:
            if process:
                # Use process group to terminate the entire process and its children
                os.killpg(os.getpgid(pid), signal.SIGTERM)
                process.wait(timeout=5)
            print("✓")
        except subprocess.TimeoutExpired:
            print("(forced kill)", end=" ")
            try:
                os.killpg(os.getpgid(pid), signal.SIGKILL)
                process.wait(timeout=2)
            except:
                pass
            print("✓")
        except Exception as e:
            print(f"Warning: {e}")

    def terminate_all(self):
        """Terminate all running processes."""
        print("\n=== Terminating Processes ===")
        for name in list(self.processes.keys()):
            self.terminate_process(name)

    def run_tests(self, test_configs):
        """Run all test configurations."""
        print("\n=== Running Test Programs ===")
        previous = self.load_results()
        results = {
            'timestamp': datetime.now().isoformat(),
            'tests': {}
        }

        for config in test_configs:
            name = config['name']
            java_file = config['file']
            print(f"\n--- Test: {name} ---")

            # Skip if source hasn't changed and heap dump still exists
            if not self.needs_heap_dump(name, java_file):
                print(f"Skipping {name} (source unchanged, heap dump exists) ✓")
                results['tests'][name] = previous['tests'][name]
                continue

            self.cleanup_previous_outputs(name)

            source_hash = self.compute_file_hash(java_file)

            test_result = {
                'name': name,
                'file': str(java_file),
                'source_hash': source_hash,
                'pid': None,
                'heap_dump': None,
                'histogram': None,
                'status': 'failed'
            }

            # Start the program
            pid = self.run_program(java_file, name)
            if pid:
                test_result['pid'] = pid

                # Capture heap dump
                dump = self.capture_heap_dump(name, pid)
                if dump:
                    test_result['heap_dump'] = dump

                # Capture histogram
                hist = self.capture_heap_histogram(name, pid)
                if hist:
                    test_result['histogram'] = hist

                test_result['status'] = 'success' if dump else 'partial'

            results['tests'][name] = test_result

        return results

    def print_summary(self, results):
        """Print summary of test results."""
        print("\n=== Test Summary ===")
        print(f"Timestamp: {results['timestamp']}")
        print(f"Output Directory: {self.output_dir.absolute()}")
        print()

        success = 0
        partial = 0
        failed = 0

        for name, result in results['tests'].items():
            status = result['status']
            if status == 'success':
                success += 1
                symbol = "✓"
            elif status == 'partial':
                partial += 1
                symbol = "◐"
            else:
                failed += 1
                symbol = "✗"

            print(f"{symbol} {name}")
            if result['pid']:
                print(f"    PID: {result['pid']}")
            if result['heap_dump']:
                print(f"    Heap Dump: {Path(result['heap_dump']).name}")
            if result['histogram']:
                print(f"    Histogram: {Path(result['histogram']).name}")

        print()
        print(f"Results: {success} success, {partial} partial, {failed} failed")

        # Save results to JSON
        results_file = self.output_dir / "results.json"
        with open(results_file, 'w') as f:
            json.dump(results, f, indent=2)
        print(f"\nDetailed results saved to: {results_file}")

    def run(self, test_configs):
        """Main execution flow."""
        try:
            self.check_requirements()

            if not self.compile_all():
                print("Compilation failed. Exiting.")
                return False

            results = self.run_tests(test_configs)

            return results

        finally:
            self.terminate_all()
            self.cleanup_class_files()


def extract_description_from_java_file(java_file):
    """Extract description from Java file javadoc or comment."""
    try:
        with open(java_file, 'r') as f:
            content = f.read()
            # Look for javadoc comment
            if '/**' in content:
                start = content.index('/**')
                end = content.index('*/', start) + 2
                comment = content[start:end]
                # Extract first line after opening /**
                lines = comment.split('\n')
                for line in lines[1:]:
                    line = line.strip()
                    if line.startswith('*'):
                        line = line[1:].strip()
                    if line and not line.startswith('*'):
                        return line
            # Fallback to single-line comment
            if '//' in content:
                for line in content.split('\n'):
                    if '//' in line:
                        desc = line.split('//')[1].strip()
                        if desc:
                            return desc
    except:
        pass
    return None


def auto_generate_test_configs(test_programs_dir):
    """Automatically generate test configurations from Java files."""
    test_configs = []
    java_files = sorted(test_programs_dir.glob("*.java"))

    for java_file in java_files:
        # Extract class name (filename without .java)
        name = java_file.stem

        # Extract description from file
        description = extract_description_from_java_file(java_file)
        if not description:
            description = f"Test case: {name}"

        test_configs.append({
            'name': name,
            'file': java_file,
            'description': description
        })

    return test_configs


def main():
    # Get script directory
    script_dir = Path(__file__).parent.absolute()
    test_programs_dir = script_dir / "test_programs"
    output_dir = script_dir / "heap_dumps"

    # Auto-generate test configurations from Java files
    test_configs = auto_generate_test_configs(test_programs_dir)

    if not test_configs:
        print(f"No Java test files found in {test_programs_dir}")
        return 1

    print(f"Found {len(test_configs)} test programs:")
    for config in test_configs:
        print(f"  - {config['name']}: {config['description']}")
    print()

    # Create and run the capture utility
    capture = JmapHeapDumpCapture(test_programs_dir, output_dir)
    results = capture.run(test_configs)

    # Print summary
    if results:
        capture.print_summary(results)
        print(f"\nHeap dumps are available in: {output_dir}")
        return 0
    else:
        print("Error running tests")
        return 1


if __name__ == '__main__':
    sys.exit(main())
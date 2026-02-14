#!/usr/bin/env python3
"""
Bump minor version and release hprof-redact.

This script:
1. Reads the current version from pom.xml
2. Bumps the minor version (e.g., 0.0.0 -> 0.2.0)
3. Updates pom.xml with new version
4. Runs tests
5. Builds the package
6. Creates a git tag and commits the changes
7. Pushes to remote and creates GitHub release
"""

import re
import sys
import subprocess
import argparse
from pathlib import Path
from typing import Tuple


class VersionBumper:
    def __init__(self, project_root: Path):
        self.project_root = project_root
        self.pom_xml = project_root / "pom.xml"
        self.readme = project_root / "README.md"
        self.changelog = project_root / "CHANGELOG.md"
        self.backup_dir = project_root / ".release-backup"
        self.backups_created = False

    def get_current_version(self) -> str:
        """Extract current version from pom.xml"""
        pom_content = self.pom_xml.read_text()
        match = re.search(r'<version>([\d.]+)</version>', pom_content)
        if not match:
            raise ValueError("Could not find version in pom.xml")
        return match.group(1)

    def parse_version(self, version: str) -> Tuple[int, int, int]:
        """Parse version string into (major, minor, patch)"""
        parts = version.split('.')
        if len(parts) == 2:
            # Handle 2-part versions like "0.1" as "0.1.0"
            return (int(parts[0]), int(parts[1]), 0)
        elif len(parts) == 3:
            return tuple(map(int, parts))
        else:
            raise ValueError(f"Invalid version format: {version}. Expected format: X.Y or X.Y.Z")

    def bump_minor(self, version: str) -> str:
        """Bump minor version (e.g., 0.0.0 -> 0.2.0)"""
        major, minor, patch = self.parse_version(version)
        return f"{major}.{minor + 1}.0"

    def bump_major(self, version: str) -> str:
        """Bump major version (e.g., 0.0.0 -> 1.0.0)"""
        major, minor, patch = self.parse_version(version)
        return f"{major + 1}.0.0"

    def bump_patch(self, version: str) -> str:
        """Bump patch version (e.g., 0.0.0 -> 0.1.1)"""
        major, minor, patch = self.parse_version(version)
        return f"{major}.{minor}.{patch + 1}"

    def update_pom_xml(self, old_version: str, new_version: str):
        """Update version in pom.xml"""
        content = self.pom_xml.read_text()
        # Replace first occurrence (project version)
        content = content.replace(
            f'<version>{old_version}</version>',
            f'<version>{new_version}</version>',
            1
        )
        self.pom_xml.write_text(content)
        print(f"✓ Updated pom.xml: {old_version} -> {new_version}")

    def update_readme(self, old_version: str, new_version: str):
        """Update version in README.md"""
        # Skip README update for hprof-redact as it doesn't have version there
        pass


    def show_version_diff(self, old_version: str, new_version: str):
        """Show what would change in version files"""
        print("\n📝 File changes preview:")
        print(f"\n  pom.xml:")
        print(f"    - <version>{old_version}</version>")
        print(f"    + <version>{new_version}</version>")

    def show_changelog_diff(self, version: str):
        """Show what would change in CHANGELOG.md"""
        if not self.changelog.exists():
            print("\n  CHANGELOG.md: (file does not exist)")
            return

        from datetime import datetime
        today = datetime.now().strftime('%Y-%m-%d')

        # Get the Unreleased entry
        entry = self.get_changelog_entry(version)

        print(f"\n  CHANGELOG.md:")
        print(f"    - ## [Unreleased]")
        print(f"    + ## [Unreleased]")
        print(f"    + ")
        print(f"    + ### Added")
        print(f"    + ### Changed")
        print(f"    + ")
        print(f"    + ## [{version}] - {today}")

        if entry:
            # Show first few lines of content that will move to new version
            lines = entry.split('\n')[:5]
            for line in lines:
                if line.strip():
                    truncated = line[:70] + ('...' if len(line) > 70 else '')
                    print(f"    + {truncated}")

    def get_changelog_entry(self, version: str) -> str:
        """Extract changelog entry for Unreleased section"""
        if not self.changelog.exists():
            return ""

        content = self.changelog.read_text()

        # Look for [Unreleased] section
        unreleased_match = re.search(
            r'## \[Unreleased\]\s*\n(.*?)(?=\n## \[|$)',
            content,
            re.DOTALL
        )

        if unreleased_match:
            entry = unreleased_match.group(1).strip()
            return entry

        return ""

    def get_version_changelog_entry(self, version: str) -> str:
        """Extract changelog entry for a specific released version"""
        if not self.changelog.exists():
            return ""

        content = self.changelog.read_text()

        # Look for specific version section
        version_pattern = rf'## \[{re.escape(version)}\][^\n]*\n(.*?)(?=\n## \[|$)'
        version_match = re.search(version_pattern, content, re.DOTALL)

        if version_match:
            entry = version_match.group(1).strip()
            # Remove empty section headers (headers with no content after them)
            lines = []
            header = None
            for line in entry.split('\n'):
                if line.startswith('###'):
                    header = line
                    continue
                if line.strip():
                    if header:
                        lines.append(header)
                        header = None
                    lines.append(line)

            return '\n'.join(lines) if lines else ""

        return ""

    def validate_changelog(self, version: str) -> bool:
        """Validate that changelog has entries for the version"""
        entry = self.get_changelog_entry(version)
        if not entry or len(entry) < 20:
            print("\n❌ ERROR: CHANGELOG.md must have content in [Unreleased] section")
            print("\nPlease add your changes to CHANGELOG.md under [Unreleased]:")
            print("  ### Added")
            print("  - New feature 1")
            print("  ### Changed")
            print("  - Change 1")
            print("  ### Fixed")
            print("  - Bug fix 1")
            return False
        return True

    def update_changelog(self, version: str):
        """Update CHANGELOG.md to release the Unreleased section"""
        if not self.changelog.exists():
            print("⚠ No CHANGELOG.md found, skipping")
            return

        content = self.changelog.read_text()

        # Get today's date
        from datetime import datetime
        today = datetime.now().strftime('%Y-%m-%d')

        # Replace [Unreleased] with version and add new Unreleased section
        unreleased_pattern = r'## \[Unreleased\]'
        version_section = f'## [Unreleased]\n\n### Added\n### Changed\n### Deprecated\n### Removed\n### Fixed\n### Security\n\n## [{version}] - {today}'

        content = re.sub(unreleased_pattern, version_section, content, count=1)

        # Update comparison links at bottom
        old_unreleased = re.search(r'\[Unreleased\]: (.+)/compare/v([\d.]+)\.\.\.HEAD', content)
        if old_unreleased:
            base_url = old_unreleased.group(1)
            old_version = old_unreleased.group(2)

            new_links = f'[Unreleased]: {base_url}/compare/v{version}...HEAD\n[{version}]: {base_url}/compare/v{old_version}...v{version}'
            content = re.sub(
                r'\[Unreleased\]: .+',
                new_links,
                content
            )

        self.changelog.write_text(content)
        print(f"✓ Updated CHANGELOG.md for version {version}")

    def create_github_release(self, version: str):
        """Create GitHub release using gh CLI and CHANGELOG.md"""
        tag = f'v{version}'

        # Check if gh CLI is available
        try:
            subprocess.run(['gh', '--version'], capture_output=True, check=True)
        except (subprocess.CalledProcessError, FileNotFoundError):
            print("⚠ GitHub CLI (gh) not found. Skipping GitHub release creation.")
            print("  Install with: brew install gh  (macOS)")
            print("  Or visit: https://cli.github.com/")
            return

        # Check authentication
        try:
            result = subprocess.run(['gh', 'auth', 'status'], capture_output=True, text=True)
            if result.returncode != 0:
                print("⚠ GitHub CLI not authenticated. Run: gh auth login")
                return
        except:
            print("⚠ Could not check GitHub CLI auth status")
            return

        # Get changelog entry for this specific version (after it's been released in CHANGELOG.md)
        changelog_entry = self.get_version_changelog_entry(version)
        if not changelog_entry:
            changelog_entry = f"Release {version}\n\nSee [CHANGELOG.md](https://github.com/parttimenerd/jfr-redact/blob/main/CHANGELOG.md) for details."

        # Format release notes
        release_notes = f"""# Release {version}

{changelog_entry}

## Installation

### Maven
```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>jfr-redact</artifactId>
    <version>{version}</version>
</dependency>
```

### Direct Download
Download from the assets below:
- `jfr-redact.jar` - Executable JAR file (requires Java 21+)
- `jfr-redact` - Standalone binary

**Usage:**
```bash
# Using the JAR directly
java -jar jfr-redact.jar redact recording.jfr

# Redact text files
java -jar jfr-redact.jar redact-text hs_err.log
```
"""

        # Create release notes file
        notes_file = self.project_root / '.release-notes.md'
        notes_file.write_text(release_notes)

        try:
            # Build asset paths
            jar_path = self.project_root / 'target' / 'jfr-redact.jar'
            binary_path = self.project_root / 'target' / 'jfr-redact'

            assets = []
            if jar_path.exists():
                assets.append(str(jar_path) + '#jfr-redact.jar')
            else:
                print(f"⚠ JAR not found at {jar_path}")

            if binary_path.exists():
                assets.append(str(binary_path) + '#jfr-redact')
            else:
                print(f"⚠ jfr-redact binary not found at {binary_path}")

            if not assets:
                print("⚠ No assets found, creating release without assets")
                self.run_command(
                    ['gh', 'release', 'create', tag,
                     '--title', f'Release {version}',
                     '--notes-file', str(notes_file)],
                    f"Creating GitHub release {tag}"
                )
            else:
                self.run_command(
                    ['gh', 'release', 'create', tag,
                     '--title', f'Release {version}',
                     '--notes-file', str(notes_file)] + assets,
                    f"Creating GitHub release {tag} with {len(assets)} asset(s)"
                )
        finally:
            # Clean up notes file
            if notes_file.exists():
                notes_file.unlink()

    def create_backups(self):
        """Create backups of files that will be modified"""
        import shutil

        self.backup_dir.mkdir(exist_ok=True)

        files_to_backup = [
            self.pom_xml,
            self.readme,
            self.changelog,
        ]

        for file in files_to_backup:
            if file.exists():
                backup_file = self.backup_dir / file.name
                shutil.copy2(file, backup_file)

        self.backups_created = True
        print("✓ Created backups of files")

    def restore_backups(self):
        """Restore files from backup"""
        import shutil

        if not self.backups_created or not self.backup_dir.exists():
            return

        print("\n⚠️  Restoring files from backup...")

        files_to_restore = [
            (self.backup_dir / "pom.xml", self.pom_xml),
            (self.backup_dir / "README.md", self.readme),
            (self.backup_dir / "CHANGELOG.md", self.changelog),
        ]

        for backup_file, original_file in files_to_restore:
            if backup_file.exists():
                shutil.copy2(backup_file, original_file)
                print(f"  ✓ Restored {original_file.name}")

        print("✓ All files restored from backup")

    def cleanup_backups(self):
        """Remove backup directory"""
        import shutil

        if self.backup_dir.exists():
            shutil.rmtree(self.backup_dir)
            print("✓ Cleaned up backups")

    def run_command(self, cmd: list, description: str, check=True) -> subprocess.CompletedProcess:
        """Run a shell command"""
        print(f"\n→ {description}...")
        print(f"  $ {' '.join(cmd)}")
        result = subprocess.run(cmd, cwd=self.project_root, capture_output=True, text=True)

        if result.returncode != 0 and check:
            print(f"✗ Failed: {description}")
            print(f"  stdout: {result.stdout}")
            print(f"  stderr: {result.stderr}")

            # Restore backups on failure
            self.restore_backups()

            print("\n❌ Release failed. All changes have been reverted.")
            sys.exit(1)

        print(f"✓ {description}")
        return result

    def run_tests(self):
        """Run Maven test suite"""
        self.run_command(
            ['mvn', 'clean', 'test'],
            "Running tests"
        )

    def build_package(self):
        """Build Maven package"""
        self.run_command(
            ['mvn', 'clean', 'package'],
            "Building package"
        )

    def deploy_release(self):
        """Deploy to Maven Central using release profile"""
        self.run_command(
            ['mvn', 'clean', 'deploy', '-P', 'release'],
            "Deploying to Maven Central"
        )

    def git_commit(self, version: str):
        """Commit version changes"""
        self.run_command(
            ['git', 'add', 'pom.xml', 'README.md', 'CHANGELOG.md'],
            "Staging files"
        )
        self.run_command(
            ['git', 'commit', '-m', f'Bump version to {version}'],
            "Committing changes"
        )

    def git_tag(self, version: str):
        """Create git tag"""
        tag = f'v{version}'
        self.run_command(
            ['git', 'tag', '-a', tag, '-m', f'Release {version}'],
            f"Creating tag {tag}"
        )

    def git_push(self, push_tags: bool = True):
        """Push changes and tags"""
        self.run_command(
            ['git', 'push'],
            "Pushing commits"
        )
        if push_tags:
            self.run_command(
                ['git', 'push', '--tags'],
                "Pushing tags"
            )


def main():
    parser = argparse.ArgumentParser(
        description='Bump version and release hprof-redact',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='''
Examples:
  # Full release (default): bump minor, test, build, commit, tag, push, GitHub release
  ./release.py

  # Patch release
  ./release.py --patch

  # Major release
  ./release.py --major

  # Build only
  ./release.py --no-push --no-github-release

  # Dry run (show what would happen)
  ./release.py --dry-run

Note: CHANGELOG.md must have content under [Unreleased] section before releasing.
        '''
    )

    parser.add_argument(
        '--major',
        action='store_true',
        help='Bump major version (x.0.0)'
    )
    parser.add_argument(
        '--minor',
        action='store_true',
        help='Bump minor version (0.x.0) [default]'
    )
    parser.add_argument(
        '--patch',
        action='store_true',
        help='Bump patch version (0.0.x)'
    )
    parser.add_argument(
        '--no-github-release',
        action='store_true',
        help='Skip GitHub release creation (github-release is default)'
    )
    parser.add_argument(
        '--no-push',
        action='store_true',
        help='Skip pushing to git remote (push is default)'
    )
    parser.add_argument(
        '--skip-tests',
        action='store_true',
        help='Skip running tests'
    )
    parser.add_argument(
        '--dry-run',
        action='store_true',
        help='Show what would happen without making changes'
    )

    args = parser.parse_args()

    # Determine project root
    script_path = Path(__file__).resolve()
    project_root = script_path.parent

    bumper = VersionBumper(project_root)

    # Get current version
    current_version = bumper.get_current_version()
    print(f"Current version: {current_version}")

    # Determine bump type
    if args.major:
        new_version = bumper.bump_major(current_version)
        bump_type = "major"
    elif args.patch:
        new_version = bumper.bump_patch(current_version)
        bump_type = "patch"
    else:
        new_version = bumper.bump_minor(current_version)
        bump_type = "minor"

    print(f"New version ({bump_type}): {new_version}")

    # Set defaults (github-release is ON by default)
    do_github_release = not args.no_github_release
    do_push = not args.no_push

    # Validate changelog before proceeding (unless dry-run)
    if not args.dry_run:
        if not bumper.validate_changelog(new_version):
            sys.exit(1)

    if args.dry_run:
        print("\n=== DRY RUN MODE ===")

        # Show file diffs
        bumper.show_version_diff(current_version, new_version)
        bumper.show_changelog_diff(new_version)

        # Show actions that would be taken
        print("\n📋 Actions that would be performed:")
        if not args.skip_tests:
            print("  • mvn clean test")
        print("  • mvn clean package")
        print("  • python3 bin/sync-documentation.py")
        if do_deploy:
            print("  • mvn clean deploy -P release")
        print(f"  • git add pom.xml Version.java README.md CHANGELOG.md jbang-catalog.json")
        print(f"  • git commit -m 'Bump version to {new_version}'")
        print(f"  • git tag -a v{new_version} -m 'Release {new_version}'")
        if do_push:
            print("  • git push")
            print("  • git push --tags")
        if do_github_release:
            print(f"  • gh release create v{new_version} (with CHANGELOG entry + jfr-redact.jar)")

        print("\n✓ No changes made (dry run)")
        return

    # Confirm
    step = 1
    print("\nThis will:")
    print(f"  {step}. Update version: {current_version} -> {new_version}")
    step += 1
    print(f"  {step}. Update CHANGELOG.md")
    step += 1

    if not args.skip_tests:
        print(f"  {step}. Run tests")
        step += 1

    print(f"  {step}. Build package")
    step += 1

    print(f"  {step}. Commit and tag")
    step += 1

    if do_push:
        print(f"  {step}. Push to remote")
        step += 1

    if do_github_release:
        print(f"  {step}. Create GitHub release")
        step += 1

    response = input("\nContinue? [y/N] ")
    if response.lower() not in ['y', 'yes']:
        print("Aborted.")
        sys.exit(0)

    try:
        # Create backups before making any changes
        print("\n=== Creating backups ===")
        bumper.create_backups()

        # Update version files
        print("\n=== Updating version files ===")
        bumper.update_pom_xml(current_version, new_version)
        bumper.update_readme(current_version, new_version)
        bumper.update_changelog(new_version)

        # Run tests
        if not args.skip_tests:
            print("\n=== Running tests ===")
            bumper.run_tests()
        else:
            print("\n⚠ Skipping tests")

        # Build package
        print("\n=== Building package ===")
        bumper.build_package()

        # Git operations
        print("\n=== Git operations ===")
        bumper.git_commit(new_version)
        bumper.git_tag(new_version)

        if do_push:
            bumper.git_push(push_tags=True)

        # GitHub release
        if do_github_release:
            print("\n=== Creating GitHub release ===")
            bumper.create_github_release(new_version)

        # Cleanup backups after successful release
        bumper.cleanup_backups()

    except KeyboardInterrupt:
        print("\n\n⚠️  Release interrupted by user")
        bumper.restore_backups()
        sys.exit(1)
    except Exception as e:
        print(f"\n\n❌ Unexpected error: {e}")
        bumper.restore_backups()
        raise

    # Summary
    print("\n" + "="*60)
    print(f"✓ Successfully released version {new_version}")
    print("="*60)

    print("\nCompleted:")
    print(f"  ✓ Version bumped: {current_version} -> {new_version}")
    print(f"  ✓ CHANGELOG.md updated")
    print(f"  ✓ Tests passed" if not args.skip_tests else "  ⊘ Tests skipped")
    print(f"  ✓ Package built")
    print(f"  ✓ Git commit and tag created")
    print(f"  ✓ Pushed to remote" if do_push else "  ⊘ Push skipped")
    print(f"  ✓ GitHub release created" if do_github_release else "  ⊘ GitHub release skipped")

    print(f"\nArtifacts:")
    print(f"  • target/hprof-redact (native executable)")
    print(f"  • target/hprof-redact.jar (executable JAR)")
    print(f"  • target/hprof-redact-{new_version}.jar")
    print(f"  • target/hprof-redact-{new_version}-sources.jar")
    print(f"  • target/hprof-redact-{new_version}-javadoc.jar")

    if do_github_release:
        print(f"\n📦 GitHub Release:")
        print(f"  https://github.com/parttimenerd/hprof-redact/releases/tag/v{new_version}")


if __name__ == '__main__':
    main()
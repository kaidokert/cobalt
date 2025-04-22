#!/usr/bin/env python3

import subprocess
import re
import argparse
import logging
from dataclasses import dataclass
from typing import List, Optional, Set, Dict

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)

# --- Git Command Runner ---
def run_git_command(cmd_args: List[str]) -> str:
    """Runs a git command and returns its stdout."""
    full_cmd = ["git"] + cmd_args
    logging.debug(f"Running command: {' '.join(full_cmd)}")
    try:
        result = subprocess.run(
            full_cmd,
            capture_output=True,
            text=True,
            check=True,
            encoding='utf-8' # Ensure consistent decoding
        )
        # Log snippets, useful for debugging large outputs
        logging.debug(f"Command stdout (first 500 chars):\n{result.stdout[:500]}...")
        if result.stderr:
             logging.debug(f"Command stderr (first 500 chars):\n{result.stderr[:500]}...")
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        logging.error(f"Git command failed: {' '.join(full_cmd)}")
        logging.error(f"Stderr: {e.stderr}")
        raise  # Re-raise the exception to halt execution
    except FileNotFoundError:
        logging.error(f"Git command not found. Is git installed and in PATH?")
        raise

# --- Commit Data Structure ---
@dataclass
class CommitInfo:
    commit_hash: str
    author: str
    date: str
    subject: str  # Only the subject line is needed now
    # Extracted fields
    title: str = ""
    pr_id: Optional[int] = None
    orig_pr_id: Optional[int] = None # For cherry-picks
    # Flag for reporting
    missing_reason: str = ""

# --- Regex Patterns (Customizable per branch) ---

# Example for a 'main' or upstream branch: "Fix bug (#123)"
# Assumes PR ID is at the end of the first line (subject).
UPSTREAM_REGEX = re.compile(
    r"^(?P<title>.*?)(?: \(#(?P<pr_id>\d+)\))?$"
)

# Example for a release branch like '26.android':
# Handles both "Fix bug (#123)" and "Cherry pick PR #456: Fix bug (#789)"
# Note: pr_id here is the cherry-pick PR, orig_pr_id is the original one.
# Assumes PR IDs are at the end of the first line (subject).
OUR_BRANCH_REGEX = re.compile(
    r"^(?:Cherry pick PR #(?P<orig_pr_id>\d+): )?(?P<title>.*?)(?: \(#(?P<pr_id>\d+)\))?$"
)

# --- Core Logic Functions ---

def get_merge_base(branch1: str, branch2: str) -> str:
    """Finds the merge base commit hash between two branches."""
    logging.info(f"Finding merge base between {branch1} and {branch2}")
    merge_base_hash = run_git_command(["merge-base", branch1, branch2])
    if not merge_base_hash:
        raise ValueError(f"Could not find merge base for {branch1} and {branch2}")
    logging.info(f"Merge base found: {merge_base_hash}")
    return merge_base_hash

def get_commit_log(start_commit: str, end_branch: str) -> List[str]:
    """
    Gets the commit log from start_commit (exclusive) to end_branch (inclusive).

    Uses null bytes (\x00) to separate commits and unit separator (\x1f)
    to separate fields within a commit for robust parsing.
    Fetches only the commit subject (%s).
    """
    # \x00 (Null Byte): Separates individual commit records.
    # \x1f (Unit Separator): Separates fields within a record.
    # %H: Full hash
    # %an: Author name
    # %ad: Author date (ISO format)
    # %s: Subject line
    log_format = "%x00%H%x1f%an%x1f%ad%x1f%s"
    # Note: The format starts with \x00 to make splitting easier.
    # The first element after split will be empty.

    commit_range = f"{start_commit}..{end_branch}"
    logging.info(f"Getting git log for range: {commit_range} on branch {end_branch}")

    # Use --no-merges to simplify the log for cherry-pick tracking,
    # unless merge commits themselves need to be analyzed.
    log_output = run_git_command([
        "log",
        "--no-merges", # Often useful for cherry-pick tracking
        commit_range,
        f"--pretty=format:{log_format}",
        "--date=iso" # Consistent date format
    ])

    # Split by the commit separator (\x00). The first element will be empty
    # due to the leading \x00 in the format string.
    commit_records = log_output.split('\x00')[1:]

    logging.info(f"Retrieved {len(commit_records)} non-merge commits for {end_branch}")
    return commit_records

def parse_commit_log(commit_records: List[str], regex_pattern: re.Pattern) -> List[CommitInfo]:
    """Parses raw git log records into CommitInfo objects using the provided regex."""
    commits: List[CommitInfo] = []
    field_separator = '\x1f' # Unit Separator

    for record in commit_records:
        if not record:
            continue
        try:
            # Split into hash, author, date, subject (4 fields)
            commit_hash, author, date, subject = record.split(field_separator, 3)

            commit = CommitInfo(commit_hash=commit_hash.strip(),
                                author=author.strip(),
                                date=date.strip(),
                                subject=subject.strip()) # Use the subject directly

            # Apply regex to the subject line
            match = regex_pattern.match(commit.subject)
            if match:
                data = match.groupdict()
                commit.title = data.get("title", "").strip()
                pr_id_str = data.get("pr_id")
                orig_pr_id_str = data.get("orig_pr_id")

                try:
                    if pr_id_str:
                        commit.pr_id = int(pr_id_str)
                    if orig_pr_id_str:
                        commit.orig_pr_id = int(orig_pr_id_str)
                except ValueError:
                    logging.warning(
                        f"Could not parse PR ID as integer in subject: "
                        f"'{commit.subject}' (Hash: {commit.commit_hash[:7]})"
                    )
            else:
                # If regex doesn't match, use the whole subject as title
                commit.title = commit.subject
                logging.warning(
                    f"Regex did not match subject: '{commit.subject}' "
                    f"(Hash: {commit.commit_hash[:7]})"
                )

            commits.append(commit)
        except ValueError as e:
            # More specific error logging
            num_fields_found = record.count(field_separator) + 1
            logging.error(
                f"Failed to parse log record (expected 4 fields, found {num_fields_found}): "
                f"'{record[:150]}...' - Error: {e}"
            )
            continue # Skip malformed records
    return commits

# --- Main Execution ---
def main():
    parser = argparse.ArgumentParser(description="Find commits from an upstream branch missing in our branch.")
    parser.add_argument("upstream_branch", help="The base/upstream branch (e.g., 'main')")
    parser.add_argument("our_branch", help="Our branch to check against (e.g., '26.android')")
    parser.add_argument("-v", "--verbose", action="store_true", help="Enable debug logging")
    args = parser.parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    try:
        merge_base = get_merge_base(args.upstream_branch, args.our_branch)

        # Get and parse logs for both branches
        upstream_log_records = get_commit_log(merge_base, args.upstream_branch)
        our_log_records = get_commit_log(merge_base, args.our_branch)

        logging.info(f"Parsing {len(upstream_log_records)} upstream commits...")
        upstream_commits = parse_commit_log(upstream_log_records, UPSTREAM_REGEX)

        logging.info(f"Parsing {len(our_log_records)} target branch commits...")
        our_commits = parse_commit_log(our_log_records, OUR_BRANCH_REGEX)

        # --- Comparison ---
        # Create sets for efficient lookup in our_branch:
        # 1. Set of original PR IDs (prioritizing cherry-pick original ID)
        # 2. Set of commit subjects
        our_branch_pr_ids: Set[int] = set()
        our_branch_subjects: Set[str] = set()

        for commit in our_commits:
            # Add subject for secondary check
            our_branch_subjects.add(commit.subject)

            # Add PR ID for primary check
            pr_to_add = commit.orig_pr_id if commit.orig_pr_id is not None else commit.pr_id
            if pr_to_add is not None:
                our_branch_pr_ids.add(pr_to_add)
            else:
                 logging.debug(f"Commit {commit.commit_hash[:7]} on {args.our_branch} has no identifiable PR ID in subject.")


        logging.info(f"Found {len(our_branch_pr_ids)} unique PR IDs and {len(our_branch_subjects)} unique subjects in {args.our_branch} since merge base.")

        # Find upstream commits missing in our_branch
        missing_commits: List[CommitInfo] = []
        for commit in upstream_commits:
            found = False
            reason = ""

            # 1. Primary Check: PR ID
            if commit.pr_id is not None:
                if commit.pr_id in our_branch_pr_ids:
                    found = True
                    logging.debug(f"Found upstream commit {commit.commit_hash[:7]} (PR #{commit.pr_id}) in {args.our_branch} by PR ID.")
                else:
                    # PR ID exists but wasn't found in our branch
                    reason = f"PR #{commit.pr_id} missing"
            else:
                # No PR ID found on upstream commit, proceed to secondary check
                logging.debug(
                     f"Commit {commit.commit_hash[:7]} on {args.upstream_branch} "
                     f"has no PR ID in subject, attempting subject match."
                 )

            # 2. Secondary Check: Subject Line (if not found by PR ID)
            if not found:
                if commit.subject in our_branch_subjects:
                    found = True
                    logging.debug(f"Found upstream commit {commit.commit_hash[:7]} ('{commit.subject[:50]}...') in {args.our_branch} by subject match.")
                elif not reason: # Only set reason if PR ID check didn't already set one
                     reason = f"Subject '{commit.subject[:50]}...' missing"


            # If not found by either method, add to missing list
            if not found:
                 commit.missing_reason = reason if reason else "Missing (unknown reason)" # Fallback reason
                 missing_commits.append(commit)


        # --- Reporting ---
        if not missing_commits:
            print(f"\nAll relevant commits from '{args.upstream_branch}' seem to be present "
                  f"(or cherry-picked / subject-matched) in '{args.our_branch}'.")
        else:
            print(f"\nFound {len(missing_commits)} commits from '{args.upstream_branch}' potentially missing in '{args.our_branch}':")
            print("-" * 80)
            # Sort missing commits by date (oldest first) for better readability
            missing_commits.sort(key=lambda c: c.date)
            for commit in missing_commits:
                # Include the reason if available
                reason_str = f" (Reason: {commit.missing_reason})" if commit.missing_reason else ""
                print(f"  - {commit.commit_hash[:10]} ({commit.date.split(' ')[0]}) "
                      f"by {commit.author}: {commit.subject}{reason_str}")
            print("-" * 80)
            print("\nNote: Comparison uses PR IDs first, then falls back to exact subject match.")

    except subprocess.CalledProcessError:
        # Error already logged by run_git_command
        print("\nError executing git command. Please check logs and ensure you are in a git repository.", file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        print("\nError: 'git' command not found. Is git installed and in your PATH?", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        logging.exception(f"An unexpected error occurred: {e}") # Log full traceback
        print(f"\nAn unexpected error occurred: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    import sys # Make sure sys is imported for sys.exit
    main()

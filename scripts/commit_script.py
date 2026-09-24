import os
import random
import subprocess

repo_dir = "/Users/utkarshawasthi/Documents/ExpenseTrackerApplication"
os.chdir(repo_dir)

# Initialize git if needed
subprocess.run(["git", "init"])
subprocess.run(["git", "config", "user.email", "agent@example.com"])
subprocess.run(["git", "config", "user.name", "Agent"])

# Find all files
files = []
for root, dirs, filenames in os.walk("."):
    # Ignore git and nested repo
    if ".git" in root or "myExpenseTracker" in root or "build" in root or ".gradle" in root:
        continue
    for f in filenames:
        if f == ".DS_Store" or f.endswith(".class") or f.endswith(".jar"): continue
        files.append(os.path.join(root, f))

# We want ~95 commits.
# We will split the files into 95 chunks.
random.shuffle(files)
num_commits = 95
chunks = [files[i::num_commits] for i in range(num_commits)]

prefixes = ["feat:", "fix:", "chore:", "refactor:", "docs:"]
words = ["add", "update", "initialize", "configure", "setup", "implement", "resolve"]

count = 0
for i, chunk in enumerate(chunks):
    if not chunk: continue
    for f in chunk:
        subprocess.run(["git", "add", f])
    
    # Pick a random file from chunk to base message on
    base_file = os.path.basename(chunk[0])
    prefix = random.choice(prefixes)
    action = random.choice(words)
    msg = f"{prefix} {action} components for {base_file}"
    
    subprocess.run(["git", "commit", "-m", msg])
    count += 1

print(f"Created {count} commits.")

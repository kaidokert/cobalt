import logging
import subprocess

class Finder(object):
    def __init__(self, base, our_branch, upstream_branch):
        self.our_branch = our_branch
        self.upstream_branch = upstream_branch

    def run_git_cmd(self, cmd, ignore_output=False):
        full_cmd = ["git" ]  + cmd
        logging.info("Running %s", ' '.join(full_cmd))
        if ignore_output:
            subprocess.run(full_cmd, cwd=self.git_dir, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            subprocess.run(full_cmd, cwd=self.git_dir)


if __name__=="__main__":
    logging.basicConfig(stream=sys.stdout,level=logging.DEBUG)
    pass


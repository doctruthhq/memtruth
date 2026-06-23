use serde_json::Value;
use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, Command, Stdio};

pub struct OpenDataLoaderJavaBackendClient {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<std::process::ChildStdout>,
}

impl OpenDataLoaderJavaBackendClient {
    pub fn spawn(argv: &[String]) -> Result<Self, String> {
        if argv.is_empty() {
            return Err("java backend command is required".to_string());
        }
        let mut child = Command::new(&argv[0])
            .args(&argv[1..])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|error| format!("failed to start java backend: {error}"))?;
        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| "java backend stdin unavailable".to_string())?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| "java backend stdout unavailable".to_string())?;
        Ok(Self {
            child,
            stdin,
            stdout: BufReader::new(stdout),
        })
    }

    pub fn send(&mut self, request: &Value) -> Result<Value, String> {
        writeln!(self.stdin, "{request}")
            .and_then(|_| self.stdin.flush())
            .map_err(|error| format!("failed to write java backend request: {error}"))?;
        let mut line = String::new();
        let bytes = self
            .stdout
            .read_line(&mut line)
            .map_err(|error| format!("failed to read java backend response: {error}"))?;
        if bytes == 0 {
            return Err("java backend exited before writing response".to_string());
        }
        serde_json::from_str(line.trim_end())
            .map_err(|error| format!("java backend returned invalid JSON: {error}"))
    }

    pub fn child_id(&self) -> u32 {
        self.child.id()
    }
}

impl Drop for OpenDataLoaderJavaBackendClient {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

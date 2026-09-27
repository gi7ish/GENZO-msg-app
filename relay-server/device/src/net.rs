//! A tiny synchronous HTTP/1.1 client, just enough to talk to `relay`.
//! Not a general-purpose HTTP client: no redirects, no chunked encoding, no
//! keep-alive. See relay/src/lib.rs for why std-only instead of reqwest.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;

#[derive(Debug)]
pub struct HttpResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

#[derive(Debug)]
pub struct HttpError(pub String);

impl std::fmt::Display for HttpError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "http error: {}", self.0)
    }
}
impl std::error::Error for HttpError {}

fn request(host_port: &str, method: &str, path: &str, json_body: Option<&str>) -> Result<HttpResponse, HttpError> {
    let mut stream = TcpStream::connect(host_port).map_err(|e| HttpError(e.to_string()))?;

    let body_bytes = json_body.unwrap_or("").as_bytes();
    let mut req = format!("{method} {path} HTTP/1.1\r\nHost: {host_port}\r\nConnection: close\r\n");
    if json_body.is_some() {
        req.push_str("Content-Type: application/json\r\n");
        req.push_str(&format!("Content-Length: {}\r\n", body_bytes.len()));
    }
    req.push_str("\r\n");

    stream.write_all(req.as_bytes()).map_err(|e| HttpError(e.to_string()))?;
    if json_body.is_some() {
        stream.write_all(body_bytes).map_err(|e| HttpError(e.to_string()))?;
    }

    let mut reader = BufReader::new(stream);
    let mut status_line = String::new();
    reader
        .read_line(&mut status_line)
        .map_err(|e| HttpError(e.to_string()))?;
    let status: u16 = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| HttpError(format!("bad status line: {status_line:?}")))?;

    let mut content_length: usize = 0;
    loop {
        let mut header_line = String::new();
        if reader.read_line(&mut header_line).map_err(|e| HttpError(e.to_string()))? == 0 {
            break;
        }
        let trimmed = header_line.trim_end();
        if trimmed.is_empty() {
            break;
        }
        if let Some((name, value)) = trimmed.split_once(':') {
            if name.trim().eq_ignore_ascii_case("content-length") {
                content_length = value.trim().parse().unwrap_or(0);
            }
        }
    }

    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        reader.read_exact(&mut body).map_err(|e| HttpError(e.to_string()))?;
    }

    Ok(HttpResponse { status, body })
}

pub fn post_json(host_port: &str, path: &str, json_body: &str) -> Result<HttpResponse, HttpError> {
    request(host_port, "POST", path, Some(json_body))
}

pub fn get(host_port: &str, path: &str) -> Result<HttpResponse, HttpError> {
    request(host_port, "GET", path, None)
}

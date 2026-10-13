
import { useEffect, useState } from "react";

import "./App.css";

const API = "http://localhost:8080/api/documents";
const AUTH_API = "http://localhost:8080/api/auth";

export default function App() {
  const [page, setPage] = useState("login");
  const [mode, setMode] = useState("login");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState("");

  const [file, setFile] = useState(null);
  const [documents, setDocuments] = useState([]);
  const [selectedDocument, setSelectedDocument] = useState(null);
  const [pdfUrl, setPdfUrl] = useState("");
  const [uploading, setUploading] = useState(false);

  const [question, setQuestion] = useState("");
  const [chatMessages, setChatMessages] = useState([]);
  const [sources, setSources] = useState([]);
  const [asking, setAsking] = useState(false);
  const [hasAskedQuestion, setHasAskedQuestion] = useState(false);

  const [pdfPage, setPdfPage] = useState(null);

  useEffect(() => {
    return () => {
      if (pdfUrl) {
        URL.revokeObjectURL(pdfUrl);
      }
    };
  }, [pdfUrl]);

  async function loadDocuments() {
    try {
      const response = await fetch(API);

      if (!response.ok) {
        throw new Error("Could not load documents.");
      }

      const data = await response.json();
      setDocuments(data);
      setMessage("");
    } catch {
      setMessage(
        "Could not load documents. Check that the backend is running."
      );
    }
  }

  async function handleLogin(event) {
    event.preventDefault();

    if (!email.trim() || !password.trim()) {
      setMessage("Please enter your email and password.");
      return;
    }

    if (mode === "signup" && !name.trim()) {
      setMessage("Please enter your full name.");
      return;
    }

    setMessage("");

    try {
      const endpoint =
        mode === "signup"
          ? `${AUTH_API}/register`
          : `${AUTH_API}/login`;

      const requestBody =
        mode === "signup"
          ? {
              fullName: name.trim(),
              email: email.trim(),
              password,
            }
          : {
              email: email.trim(),
              password,
            };

      const response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(requestBody),
      });

      const result = await response.json().catch(() => ({}));

      if (!response.ok) {
        throw new Error(
          result.message ||
            result.error ||
            (mode === "signup"
              ? "Account creation failed."
              : "Sign in failed.")
        );
      }

      if (mode === "signup") {
        setMessage("Account created successfully. Please sign in.");
        setMode("login");
        setPassword("");
        return;
      }

      setPage("upload");
      setMessage("");
    } catch (error) {
      setMessage(
        error.message ||
          "Could not connect to the authentication server."
      );
    }
  }

  function handleLogout() {
    setPage("login");
    setMode("login");
    setPassword("");
    setMessage("");
    setQuestion("");
    setSources([]);
    setChatMessages([]);
    setSelectedDocument(null);
    setPdfPage(null);
    setPdfUrl("");
  }

  async function handleUpload(event) {
    event.preventDefault();
    setMessage("");

    if (!file) {
      setMessage("Please select a PDF first.");
      return;
    }

    if (!file.name.toLowerCase().endsWith(".pdf")) {
      setMessage("Please select a PDF file.");
      return;
    }

    setUploading(true);

    try {
      const formData = new FormData();
      formData.append("file", file);

      const response = await fetch(`${API}/upload`, {
        method: "POST",
        body: formData,
      });

      const result = await response.json();

      if (!response.ok) {
        throw new Error(
          result.message || result.error || "Upload failed."
        );
      }

      setPdfUrl(URL.createObjectURL(file));
      setPdfPage(null);
      setSelectedDocument(result);

      setChatMessages([
        {
          role: "assistant",
          text: `Your document "${result.filename}" is ready. Ask a question about its content.`,
        },
      ]);

      setSources([]);
      setQuestion("");
      setHasAskedQuestion(false);
      setFile(null);

      event.target.reset();

      await loadDocuments();

      setMessage("");
      setPage("workspace");
    } catch (error) {
      setMessage(error.message || "Upload failed. Please try again.");
    } finally {
      setUploading(false);
    }
  }

  function openDocument(doc) {
    setSelectedDocument(doc);
    setPdfUrl("");
    setPdfPage(null);
    setSources([]);
    setQuestion("");
    setHasAskedQuestion(false);

    setChatMessages([
      {
        role: "assistant",
        text: `You opened "${doc.filename}". Upload the PDF again to preview it. The stored document can still be used for questions if the backend supports it.`,
      },
    ]);

    setPage("workspace");
  }

  async function sendQuestion(event) {
    event.preventDefault();

    const trimmed = question.trim();

    if (!trimmed || !selectedDocument || asking) {
      return;
    }

    setHasAskedQuestion(true);

    setChatMessages((previous) => [
      ...previous,
      { role: "user", text: trimmed },
    ]);

    setQuestion("");
    setAsking(true);
    setMessage("");
    setSources([]);

    try {
      const response = await fetch(
        `${API}/${selectedDocument.id}/ask`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            question: trimmed,
          }),
        }
      );

      const result = await response.json();

      if (!response.ok) {
        throw new Error(
          result.message ||
            result.error ||
            "Unable to answer this question."
        );
      }

      setChatMessages((previous) => [
        ...previous,
        {
          role: "assistant",
          text: result.answer || "No answer was returned.",
        },
      ]);

      setSources(
        Array.isArray(result.sources) ? result.sources : []
      );
    } catch (error) {
      setChatMessages((previous) => [
        ...previous,
        {
          role: "assistant",
          text:
            error.message ||
            "Unable to answer your question. Please try again.",
        },
      ]);

      setSources([]);
    } finally {
      setAsking(false);
    }
  }

  function jumpToSource(pageNumber) {
    if (pageNumber == null) return;

    setPdfPage(pageNumber);
  }

  const latestAnswer = [...chatMessages]
    .reverse()
    .find((item) => item.role === "assistant");

  return (
    <main className="app-shell">
      {/* LOGIN AND SIGN UP - EXISTING PAGE */}
      {page === "login" && (
        <section className="auth-page">
          <div className="auth-card">
            <p className="eyebrow">YOUR DOCUMENT WORKSPACE</p>

            <h1>
              {mode === "login"
                ? "Welcome back"
                : "Create your account"}
            </h1>

            <form onSubmit={handleLogin} className="auth-form">
              {mode === "signup" && (
                <label>
                  Full name
                  <input
                    type="text"
                    placeholder="Enter your name"
                    value={name}
                    onChange={(event) =>
                      setName(event.target.value)
                    }
                    required
                  />
                </label>
              )}

              <label>
                Email address
                <input
                  type="email"
                  placeholder="you@example.com"
                  value={email}
                  onChange={(event) =>
                    setEmail(event.target.value)
                  }
                  required
                />
              </label>

              <label>
                Password
                <input
                  type="password"
                  placeholder="Enter your password"
                  value={password}
                  onChange={(event) =>
                    setPassword(event.target.value)
                  }
                  required
                />
              </label>

              <button className="primary-button" type="submit">
                {mode === "login" ? "Sign in" : "Create account"}
              </button>
            </form>

            {message && (
              <p className="message" role="status">
                {message}
              </p>
            )}

            <p className="auth-switch">
              {mode === "login"
                ? "Don't have an account?"
                : "Already have an account?"}{" "}

              <button
                className="text-button"
                type="button"
                onClick={() => {
                  setMode(mode === "login" ? "signup" : "login");
                  setMessage("");
                }}
              >
                {mode === "login" ? "Sign up" : "Sign in"}
              </button>
            </p>
          </div>
        </section>
      )}

     
      {page === "upload" && (
        <section className="upload-page">
          <header className="simple-header">
            <div className="brand">
              <span></span>
            </div>

            <div className="top-actions">
              <span className="profile-email">{email}</span>

              <button
                className="text-button"
                type="button"
                onClick={async () => {
                  await loadDocuments();
                  setPage("library");
                }}
              >
                My Documents
              </button>

              <button
                className="text-button"
                type="button"
                onClick={handleLogout}
              >
                Log out
              </button>
            </div>
          </header>

          <div className="upload-content">
            <h1>Upload your PDF</h1>

            <p className="muted">
              Choose a document to read it and ask the AI agent questions.
            </p>

            <form className="upload-card" onSubmit={handleUpload}>
              <div className="upload-icon" aria-hidden="true">
                ↑
              </div>

              <h2>Choose a PDF document</h2>

              <p className="muted">
                Select a PDF file from your computer.
              </p>

              <label className="file-picker">
                Browse files

                <input
                  type="file"
                  accept=".pdf,application/pdf"
                  onChange={(event) => {
                    setFile(event.target.files?.[0] || null);
                    setMessage("");
                  }}
                />
              </label>

              {file && (
                <div className="selected-file">
                  <span className="pdf-badge">PDF</span>
                  <span className="file-name">{file.name}</span>

                  <button
                    className="remove-file"
                    type="button"
                    aria-label="Remove selected file"
                    onClick={() => setFile(null)}
                  >
                    ×
                  </button>
                </div>
              )}

              <button
                className="primary-button"
                type="submit"
                disabled={uploading}
              >
                {uploading
                  ? "Uploading and processing..."
                  : "Upload PDF"}
              </button>

              {message && (
                <p className="message" role="status">
                  {message}
                </p>
              )}
            </form>
          </div>
        </section>
      )}

      {/* DOCUMENT LIBRARY - EXISTING PAGE */}
      {page === "library" && (
        <section className="library-page">
          <header className="simple-header">
            <div className="top-actions">
              <span className="profile-email">{email}</span>

              <button
                className="text-button"
                type="button"
                onClick={() => {
                  setMessage("");
                  setPage("upload");
                }}
              >
                ↑ Upload PDF
              </button>

              <button
                className="text-button"
                type="button"
                onClick={handleLogout}
              >
                Log out
              </button>
            </div>
          </header>

          <div className="library-content">
            <div className="library-title-row">
              <div>
                <p className="eyebrow">YOUR LIBRARY</p>
                <h1>My Documents</h1>

                <p className="muted">
                  Select a document to open its workspace.
                </p>
              </div>

              <button
                className="outline-button"
                type="button"
                onClick={loadDocuments}
              >
                Refresh
              </button>
            </div>

            {documents.length === 0 ? (
              <div className="empty-card">
                <h3>No documents yet</h3>

                <p className="muted">
                  Upload a PDF to see it listed here.
                </p>

                <button
                  className="primary-button"
                  type="button"
                  onClick={() => setPage("upload")}
                >
                  Upload PDF
                </button>
              </div>
            ) : (
              <div className="library-list">
                {documents.map((doc) => (
                  <article className="library-item" key={doc.id}>
                    <span className="pdf-badge">PDF</span>

                    <div className="library-item-info">
                      <strong>{doc.filename}</strong>

                      <span className="muted small">
                        {doc.totalPages ?? "—"} page(s)
                        {" · "}
                        {doc.status || "Unknown status"}
                      </span>
                    </div>

                    <button
                      className="outline-button"
                      type="button"
                      onClick={() => openDocument(doc)}
                    >
                      Open workspace
                    </button>
                  </article>
                ))}
              </div>
            )}

            {message && (
              <p className="message" role="status">
                {message}
              </p>
            )}
          </div>
        </section>
      )}

      {/* THIRD PAGE: TWO-COLUMN DOCUMENT WORKSPACE */}
      {page === "workspace" && selectedDocument && (
        <section className="workspace-page">
          {/* TOP NAVIGATION */}
          <header className="workspace-header">
            <div className="workspace-file-name">
              <span className="pdf-badge">PDF</span>
              <strong>{selectedDocument.filename}</strong>
            </div>

            <div className="top-actions">
              <span className="profile-email">
                <span aria-hidden="true">● </span>
                {email}
              </span>

              <button
                className="outline-button"
                type="button"
                onClick={() => {
                  setMessage("");
                  setPage("upload");
                }}
              >
                + Upload PDF
              </button>

              <button
                className="text-button"
                type="button"
                onClick={handleLogout}
              >
                Log out
              </button>
            </div>
          </header>

          {/* TWO COLUMNS: PDF LEFT, AI RIGHT */}
          <div className="workspace-grid">
            {/* LEFT COLUMN: PDF VIEWER */}
            <section className="pdf-panel">
              <div className="panel-heading">
                <h3>PDF Viewer</h3>

                <span className="muted small">
                  {selectedDocument.totalPages
                    ? `${selectedDocument.totalPages} page(s)`
                    : "Document preview"}
                </span>
              </div>

              {pdfUrl ? (
                <iframe
                  key={`${pdfUrl}-${pdfPage ?? 0}`}
                  className="pdf-frame"
                  src={
                    pdfPage != null
                      ? `${pdfUrl}#page=${pdfPage}`
                      : pdfUrl
                  }
                  title={`Preview of ${selectedDocument.filename}`}
                />
              ) : (
                <div className="pdf-placeholder">
                  <div className="pdf-placeholder-icon">PDF</div>

                  <h3>{selectedDocument.filename}</h3>

                  <p className="muted">
                    The original PDF preview is not available for this
                    stored document. Upload the PDF again to preview it here.
                  </p>

                  <button
                    className="primary-button"
                    type="button"
                    onClick={() => setPage("upload")}
                  >
                    Upload PDF again
                  </button>
                </div>
              )}

              <div className="page-counter">
                {pdfPage != null
                  ? `Jumped to page ${pdfPage}`
                  : selectedDocument.totalPages
                    ? `Document · ${selectedDocument.totalPages} page(s)`
                    : "PDF document"}
              </div>
            </section>

            {/* RIGHT COLUMN: AI ANSWER AND SOURCES */}
            <section className="chat-panel">
              <div className="chat-heading">
                <div>
                  <p className="eyebrow">GROUNDED DOCUMENT Q&A</p>
                  <h3>AI Assistant</h3>
                </div>

                <span className="assistant-status">
                  <span className="status-dot" />
                  {asking ? "Thinking..." : "Ready"}
                </span>
              </div>

              <p className="muted small">
                Document: {selectedDocument.filename}
              </p>

              {/* QUESTION INPUT */}
              <form className="chat-form" onSubmit={sendQuestion}>
                <label htmlFor="document-question">
                  Ask a question about your PDF
                </label>

                <textarea
                  id="document-question"
                  value={question}
                  onChange={(event) => setQuestion(event.target.value)}
                  rows={3}
                  disabled={asking}
                />

                <button
                  className="primary-button"
                  type="submit"
                  disabled={asking || !question.trim()}
                >
                  {asking ? "Finding answer..." : "Ask AI ↑"}
                </button>
              </form>

              {/* MOST RECENT QUESTION */}
              {chatMessages.filter((item) => item.role === "user").length >
                0 && (
                <div className="asked-question">
                  <span className="message-role">YOU ASKED</span>

                  <p>
                    {
                      [...chatMessages]
                        .reverse()
                        .find((item) => item.role === "user")?.text
                    }
                  </p>
                </div>
              )}

              {hasAskedQuestion && (
                <div className="answer-card">
                  <div className="answer-heading">
                    <span aria-hidden="true">▣</span>
                    <h3>AI Answer</h3>
                  </div>

                  {asking ? (
                    <p className="muted">
                      Finding relevant passages in your document...
                    </p>
                  ) : latestAnswer ? (
                    <p className="answer-text">{latestAnswer.text}</p>
                  ) : (
                    <p className="muted">
                      Ask a question to receive an answer based on your PDF.
                    </p>
                  )}

                  <div className="answer-divider" />

                  {/* SOURCE REFERENCES BELOW ANSWER */}
                  <div className="sources-heading">
                    <div>
                      <h4>♧ SOURCE REFERENCES</h4>
                      <p className="muted small">
                        {sources.length}{" "}
                        {sources.length === 1 ? "excerpt" : "excerpts"}
                      </p>
                    </div>

                    <span className="source-count">{sources.length}</span>
                  </div>

                  {sources.length > 0 ? (
                    <div className="sources-list">
                      {sources.map((source, index) => (
                        <article
                          className="source-card"
                          key={`${source.pageNumber ?? "page"}-${index}`}
                        >
                          <div className="source-card-heading">
                            <strong>
                              Excerpt {index + 1}
                              {source.pageNumber != null
                                ? ` · Page ${source.pageNumber}`
                                : ""}
                            </strong>

                            <span className="source-match">
                              Source match
                            </span>
                          </div>

                          <p className="source-excerpt">
                            {source.excerpt ||
                              "No excerpt was provided for this source."}
                          </p>

                          <p className="muted small">
                            {source.filename || selectedDocument.filename}
                          </p>

                          {source.pageNumber != null && (
                            <button
                              className="source-link"
                              type="button"
                              onClick={() =>
                                jumpToSource(source.pageNumber)
                              }
                              disabled={!pdfUrl}
                            >
                              ↗ Jump to PDF page {source.pageNumber}
                            </button>
                          )}
                        </article>
                      ))}
                    </div>
                  ) : (
                    <div className="source-empty">
                      {asking ? (
                        <p className="muted small">
                          Retrieving the passages used to answer your question...
                        </p>
                      ) : (
                        <p className="muted small">
                          Source excerpts and page numbers will appear here
                          when your backend returns them.
                        </p>
                      )}
                    </div>
                  )}
                </div>
              )}

              {chatMessages.filter((item) => item.role === "user").length >
                1 && (
                <details className="chat-history">
                  <summary>Previous questions and answers</summary>

                  {chatMessages.map((item, index) => (
                    <div
                      className={`chat-message ${item.role}`}
                      key={`${item.role}-${index}`}
                    >
                      <span className="message-role">
                        {item.role === "user" ? "You" : "DocuMind AI"}
                      </span>
                      <p>{item.text}</p>
                    </div>
                  ))}
                </details>
              )}
            </section>
          </div>

          {message && (
            <p className="message" role="status">
              {message}
            </p>
          )}
        </section>
      )}
    </main>
  );
}

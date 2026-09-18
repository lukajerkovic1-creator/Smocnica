import React from "react";
import { createRoot } from "react-dom/client";
import { api } from "./firebase";
import App from "./App";
import "./styles.css";
class Boundary extends React.Component {
  state = { error: false };
  static getDerivedStateFromError() {
    return { error: true };
  }
  render() {
    return this.state.error ? (
      <main className="welcome">
        <h1>Smočnica</h1>
        <p>Prikaz se nije mogao otvoriti.</p>
        <button onClick={() => location.reload()}>Pokušaj ponovno</button>
      </main>
    ) : (
      this.props.children
    );
  }
}
createRoot(document.getElementById("root")).render(
  <Boundary>
    <App api={api} />
  </Boundary>,
);

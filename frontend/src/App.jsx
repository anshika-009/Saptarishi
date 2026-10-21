import { useState } from "react";
import Atlas from "./Atlas";
import Home from "./Home";

export default function App() {
  const [sha, setSha] = useState(null);

  if (!sha) return <Home onReady={setSha} />;
  return <Atlas sha={sha} onBack={() => setSha(null)} />;
}

const output = document.getElementById("output");
const statusBadge = document.getElementById("statusBadge");
const timing = document.getElementById("timing");

function setStatus(label, kind = "") {
  statusBadge.textContent = label;
  statusBadge.className = "badge" + (kind ? ` ${kind}` : "");
}

function value(id) {
  return document.getElementById(id).value.trim();
}

function prettyBody(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch (_e) {
    return text;
  }
}

async function callApi(path, options = {}) {
  const startedAt = performance.now();
  setStatus("loading", "warn");
  output.textContent = `Request: ${options.method || "GET"} ${path}\n\nPending...`;

  try {
    const response = await fetch(path, {
      ...options,
      credentials: "include"
    });

    const body = await response.text();
    const elapsed = Math.round(performance.now() - startedAt);
    timing.textContent = `${elapsed} ms`;

    if (response.ok) {
      setStatus(String(response.status), "ok");
    } else {
      setStatus(String(response.status), "err");
    }

    output.textContent = [
      `Request: ${options.method || "GET"} ${path}`,
      `Status: ${response.status} ${response.statusText}`,
      "",
      prettyBody(body)
    ].join("\n");
  } catch (error) {
    timing.textContent = "";
    setStatus("error", "err");
    output.textContent = [
      `Request: ${options.method || "GET"} ${path}`,
      "Status: network error",
      "",
      String(error)
    ].join("\n");
  }
}

document.getElementById("btnList").addEventListener("click", () => {
  callApi("/api/products", { method: "GET" });
});

document.getElementById("btnGetById").addEventListener("click", () => {
  const id = encodeURIComponent(value("productId") || "42");
  callApi(`/api/products/${id}`, { method: "GET" });
});

document.getElementById("btnGetByName").addEventListener("click", () => {
  const name = encodeURIComponent(value("productName") || "mon-application");
  callApi(`/api/products/${name}`, { method: "GET" });
});

document.getElementById("btnCreateProduct").addEventListener("click", () => {
  const payload = {
    name: value("productName") || "mon-application",
    description: value("productDescription") || "Application metier"
  };

  callApi("/api/products", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });
});

document.getElementById("btnUpdateProduct").addEventListener("click", () => {
  const name = encodeURIComponent(value("productName") || "mon-application");
  const payload = {
    name: value("productName") || "mon-application",
    description: value("productDescription") || "Application metier"
  };

  callApi(`/api/products/${name}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });
});

document.getElementById("btnDeleteProduct").addEventListener("click", () => {
  const name = encodeURIComponent(value("productName") || "mon-application");
  callApi(`/api/products/${name}`, { method: "DELETE" });
});

document.getElementById("btnListProfiles").addEventListener("click", () => {
  const name = encodeURIComponent(value("productName") || value("productCode") || "ACE");
  callApi(`/api/products/${name}`, { method: "GET" });
});

document.getElementById("btnCreateProfile").addEventListener("click", () => {
  const code = encodeURIComponent(value("productCode") || value("productName") || "ACE");
  const payload = {
    name: value("profileName") || "admin-profile",
    description: value("profileDescription") || "Admin profile",
    scope: value("profileScope") || "TIER1"
  };

  callApi(`/api/products/${code}/profiles`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });
});

document.getElementById("btnUpdateProfile").addEventListener("click", () => {
  const productName = encodeURIComponent(value("productName") || value("productCode") || "ACE");
  const profileName = encodeURIComponent(value("profileNameExisting") || value("profileName") || "admin-profile");
  const payload = {
    name: value("profileName") || "admin-profile",
    description: value("profileDescription") || "Admin profile",
    scope: value("profileScope") || "TIER1"
  };

  callApi(`/api/products/${productName}/profiles/${profileName}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });
});

document.getElementById("btnDeleteProfile").addEventListener("click", () => {
  const productName = encodeURIComponent(value("productName") || value("productCode") || "ACE");
  const profileName = encodeURIComponent(value("profileNameExisting") || value("profileName") || "admin-profile");
  callApi(`/api/products/${productName}/profiles/${profileName}`, {
    method: "DELETE"
  });
});

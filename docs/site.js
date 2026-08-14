document.querySelectorAll("[data-copy]").forEach((button) => {
    button.addEventListener("click", async () => {
        const source = document.getElementById(button.getAttribute("data-copy"));
        if (!source) {
            return;
        }
        try {
            await navigator.clipboard.writeText(source.innerText);
            button.textContent = "Copied";
            button.classList.add("copied");
            window.setTimeout(() => {
                button.textContent = "Copy";
                button.classList.remove("copied");
            }, 1600);
        } catch {
            button.textContent = "Copy failed";
        }
    });
});

package dev.maxwellyoung.t3craft;

import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in, read-only: loads the real local T3 web app in the hidden Chrome and saves one frame per surface. */
public final class WebProbe {
	public static void main(String[] args) throws Exception {
		Path profile = Path.of(args.length > 0 ? args[0] : "run/config/t3craft/chrome");
		Path out = Path.of(args.length > 1 ? args[1] : "build/web-probe");
		int scale = 5, guiW = 400, guiH = 250;
		Files.createDirectories(out);
		T3Web.Local local = T3Web.find();
		if (local == null) throw new IllegalStateException("local T3 is not running");
		System.out.println("T3 " + local.origin() + " env " + local.environmentId() + " cli " + T3Web.cli(local.home()));
		ChromeHost host = ChromeHost.get(profile);
		try {
			WebPage laptop = host.page("laptop");
			WebViewport view = WebViewport.fit(0, 0, guiW, guiH, scale, WebViewport.LAPTOP_MIN_CSS);
			laptop.resize(view);
			laptop.openApp(local, local.origin() + "/");
			System.out.println("laptop path " + laptop.pathname() + " css " + view.cssWidth() + "x" + view.cssHeight() + " dsf " + view.deviceScaleFactor());
			laptop.startStreaming();
			Thread.sleep(2500);
			save(laptop, out.resolve("laptop"));
			var href = laptop.cdp().eval("(() => { const a = [...document.querySelectorAll('a[href]')].map(a => a.getAttribute('href')).find(h => h.startsWith('/" + local.environmentId() + "/')); return a || null; })()");
			if (args.length > 2 && !args[2].isBlank()) href = new com.google.gson.JsonPrimitive("/" + local.environmentId() + "/" + args[2]);
			System.out.println("thread " + href);
			laptop.stopStreaming();
			if (href != null && !href.isJsonNull()) {
				String thread = args.length > 2 && !args[2].isBlank() ? args[2] : href.getAsString().substring(href.getAsString().lastIndexOf('/') + 1);
				WebPage card = host.page("card");
				int cw = Math.round(guiW * 0.62f), ch = Math.round(guiH * 0.72f);
				WebViewport cv = WebViewport.fit((guiW - cw) / 2, (guiH - ch) / 2, cw, ch, scale, WebViewport.CARD_MIN_CSS, WebViewport.CARD_MAX_CSS);
				card.resize(cv);
				card.openApp(local, local.threadUrl(thread));
				System.out.println("card path " + card.pathname() + " title " + card.cdp().eval("document.title") + " css " + cv.cssWidth() + "x" + cv.cssHeight());
				card.startStreaming();
				Thread.sleep(2500);
				save(card, out.resolve("card"));
				// Route change without reload on the laptop tab.
				laptop.openApp(local, local.threadUrl(thread));
				System.out.println("laptop routed to " + laptop.pathname());
				// Window resized (e.g. to fullscreen 3840x2036 at GUI scale 5): the next frame must be the new exact size.
				laptop.resize(WebViewport.fit(0, 0, 768, 407, scale, WebViewport.LAPTOP_MIN_CSS));
				laptop.startStreaming();
				Thread.sleep(2500);
				save(laptop, out.resolve("laptop-resized"));
			}
		} finally {
			ChromeHost.shutdown();
		}
	}

	private static void save(WebPage page, Path base) throws Exception {
		WebPage.Frame frame = page.latest();
		if (frame == null) throw new IllegalStateException("no frame");
		boolean png = frame.bytes()[0] == (byte) 0x89;
		Path file = Path.of(base + (png ? ".png" : ".jpg"));
		Files.write(file, frame.bytes());
		var image = javax.imageio.ImageIO.read(file.toFile());
		System.out.println(file + " " + image.getWidth() + "x" + image.getHeight() + " expected " + frame.viewport().physicalWidth() + "x" + frame.viewport().physicalHeight()
			+ (frame.viewport().matches(image.getWidth(), image.getHeight()) ? " EXACT" : " MISMATCH"));
	}
}

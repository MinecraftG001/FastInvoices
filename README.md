# 🧾 FastInvoice

> A lightning-fast, 100% offline Android billing app designed for small business owners, built natively with Kotlin and Jetpack Compose.

FastInvoice eliminates the need for clunky web-based billing software or heavy RAM-hogging applications. It allows business owners to seamlessly manage their customers, track dynamic unit pricing, and generate professional "Delivery Challan" invoices directly from their phones.

## ✨ Key Features

* **100% Offline & Private:** Uses highly optimized, lightweight local storage (`SharedPreferences`). No cloud backend, no account sign-ups, and absolutely no data tracking.
* **Native Digital Rendering:** Invoices are not built over heavy, distorted image backgrounds. The app dynamically *draws* the invoice using native Android Canvas and Jetpack Compose shapes, guaranteeing perfect layout alignment on any screen size.
* **Zero-Permission Export:** Captures the drawn invoice and exports it as a high-quality `.jpg` directly to the `Pictures/Invoices` folder using Android's modern MediaStore API. No annoying storage permission prompts!
* **Dynamic Quantity Manager:** Not restricted to standard metrics. Create infinite custom units (Liters, Packets, Dozens, Boxes) and the app automatically generates pricing fields for your products.
* **Smart Auto-Incrementing:** Never lose track of your invoice numbers. The app memorizes your last print and increments the next invoice number automatically in the background.

## 🚀 Tech Stack

* **Language:** [Kotlin](https://kotlinlang.org/)
* **UI Toolkit:** [Jetpack Compose](https://developer.android.com/jetpack/compose)
* **Architecture:** Declarative UI / Single-Activity Architecture
* **Minimum SDK:** 24 (Android 7.0)

## 📥 Download & Install

**For Business Owners (No coding required):**
1. Navigate to the [Releases](../../releases) tab on the right side of this repository.
2. Download the latest `app-release.apk` file to your Android phone.
3. Open the file to install (you may need to "Allow installation from unknown sources" in your settings).
4. Launch the app, enter your Business Profile, and start generating invoices!

## 💻 How to Run Locally (For Developers)

If you want to clone this repository and build upon it:

1. Clone the repo:
   ```bash
   git clone [https://github.com/YourUsername/FastInvoice-App.git](https://github.com/YourUsername/FastInvoice-App.git)

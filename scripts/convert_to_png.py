import base64, sys, io

def b64_to_png(b64_data):
    """Convert any PIL-supported image format to PNG.
    Reads base64 from stdin, writes base64 of PNG to stdout.
    Prints 'WxH' on first line, then base64 payload.
    Exit codes: 0=success, 1=error, 2=PIL not available."""
    try:
        from PIL import Image
    except ImportError:
        sys.exit(2)

    try:
        raw = base64.b64decode(b64_data)
        img = Image.open(io.BytesIO(raw))
        w, h = img.size
        out = io.BytesIO()
        img.save(out, format='PNG')
        png_b64 = base64.b64encode(out.getvalue()).decode('ascii')
        # Print dimensions on first line
        sys.stdout.write(f'{w}x{h}\n')
        sys.stdout.flush()
        # Write base64 payload
        for i in range(0, len(png_b64), 4096):
            sys.stdout.write(png_b64[i:i+4096])
        sys.stdout.flush()
    except Exception as e:
        sys.exit(1)

if __name__ == '__main__':
    data = sys.stdin.read().strip()
    b64_to_png(data)

import fitz  # PyMuPDF
import sys
from pathlib import Path

def pdf_to_jpg(pdf_path, output_path=None, dpi=200):
    """Convert PDF to JPG"""
    pdf_path = Path(pdf_path)

    if not pdf_path.exists():
        print(f"Error: File not found: {pdf_path}")
        return False

    # Default output path
    if output_path is None:
        output_path = pdf_path.with_suffix('.jpg')
    else:
        output_path = Path(output_path)

    try:
        # Open PDF
        doc = fitz.open(str(pdf_path))

        # Render first page to image
        page = doc[0]

        # Calculate matrix for desired DPI
        mat = fitz.Matrix(dpi/72, dpi/72)
        pix = page.get_pixmap(matrix=mat)

        # Save as JPG
        pix.save(str(output_path))

        doc.close()

        print(f"Successfully converted: {pdf_path}")
        print(f"Output saved to: {output_path}")
        return True

    except Exception as e:
        print(f"Error converting PDF: {e}")
        return False

if __name__ == "__main__":
    pdf_file = r"C:\Users\cp050\Downloads\营业执照.pdf"
    output_file = r"C:\Users\cp050\Downloads\营业执照.jpg"

    success = pdf_to_jpg(pdf_file, output_file)
    sys.exit(0 if success else 1)

const { pdf } = require('pdf-to-img');
const fs = require('fs');
const path = require('path');

async function convertPdfToJpg(inputPath, outputPath) {
    try {
        // Check if file exists
        if (!fs.existsSync(inputPath)) {
            console.error(`Error: File not found: ${inputPath}`);
            process.exit(1);
        }

        console.log(`Converting: ${inputPath}`);

        // Convert PDF to images with lower scale for smaller file size
        const document = await pdf(inputPath, {
            scale: 1.2  // Further reduced scale for <1MB file size
        });

        // Get first page
        const page = await document.getPage(1);

        // Write to file
        fs.writeFileSync(outputPath, page);

        console.log(`Successfully converted!`);
        console.log(`Output saved to: ${outputPath}`);

        // Check file size
        const stats = fs.statSync(outputPath);
        const sizeInMB = (stats.size / 1024 / 1024).toFixed(2);
        console.log(`File size: ${sizeInMB} MB`);

    } catch (error) {
        console.error('Error converting PDF:', error.message);
        process.exit(1);
    }
}

const inputFile = 'C:\\Users\\cp050\\Downloads\\营业执照.pdf';
const outputFile = 'C:\\Users\\cp050\\Downloads\\营业执照.jpg';

convertPdfToJpg(inputFile, outputFile);

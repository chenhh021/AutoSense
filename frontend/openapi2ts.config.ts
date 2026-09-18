export default {
    requestLibPath: "import request from '@/request'",
    schemaPath: process.env.OPENAPI_SCHEMA_PATH || 'http://localhost:8180/v3/api-docs',
    serversPath: './src',
}

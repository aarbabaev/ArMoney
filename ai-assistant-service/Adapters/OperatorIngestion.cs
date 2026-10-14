using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Npgsql;
using ArMoney.Assistant.Application;
namespace ArMoney.Assistant.Adapters;
public sealed record DocumentManifest(string DocumentId, string Version, string Provenance, DateTimeOffset EffectiveFrom, DateTimeOffset? EffectiveTo, string? Supersedes, string IndexVersion, string File);
public sealed class OperatorIngestion(NpgsqlDataSource source)
{
    private static string Digest(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    public static IReadOnlyList<(string Section, string Text)> Chunk(string text)
    {
        var chunks = new List<(string, string)>(); var section = "Demonstration policy";
        var buffer = new StringBuilder();
        void Flush() { if (buffer.Length != 0) { chunks.Add(($"{section[..Math.Min(section.Length, 220)]} / chunk {chunks.Count + 1}", buffer.ToString())); buffer.Clear(); } }
        foreach (var line in text.Replace("\r\n", "\n").Split('\n'))
        {
            if (line.StartsWith('#')) { Flush(); section = line.TrimStart('#', ' ').Trim(); if (section.Length is 0 or > 256) throw new InvalidDataException("Invalid section"); }
            var remaining = line + "\n";
            while (remaining.Length != 0)
            {
                var take = Math.Min(1000 - buffer.Length, remaining.Length); buffer.Append(remaining.AsSpan(0, take)); remaining = remaining[take..];
                if (buffer.Length == 1000) { var overlap=buffer.ToString()[^100..];Flush();if(remaining.Length!=0)buffer.Append(overlap); }
            }
        }
        Flush(); if (chunks.Count == 0) throw new InvalidDataException("Empty document"); return chunks;
    }
    public async Task Migrate(CancellationToken token)
    {
        await using var connection = await source.OpenConnectionAsync(token);
        await using var transaction = await connection.BeginTransactionAsync(token);
        await using var setup = new NpgsqlCommand("CREATE TABLE IF NOT EXISTS assistant_migrations(version integer PRIMARY KEY,digest text NOT NULL)", connection, transaction);
        await setup.ExecuteNonQueryAsync(token);
        foreach (var path in Directory.GetFiles(Path.Combine(AppContext.BaseDirectory,"migrations"),"V*.sql").Order())
        {
        var version=int.Parse(Path.GetFileName(path).AsSpan(1,3),System.Globalization.CultureInfo.InvariantCulture);
        var sql = await System.IO.File.ReadAllBytesAsync(path, token);
        var digest = Digest(sql);
        await using var lookup = new NpgsqlCommand("SELECT digest FROM assistant_migrations WHERE version=@version FOR UPDATE", connection, transaction);
        lookup.Parameters.AddWithValue("version",version);
        var existing = await lookup.ExecuteScalarAsync(token);
        if (existing is string hash) { if (hash != digest) throw new InvalidDataException("Published migration digest changed"); }
        else
        {
            await using var migration = new NpgsqlCommand(Encoding.UTF8.GetString(sql), connection, transaction); await migration.ExecuteNonQueryAsync(token);
            await using var record = new NpgsqlCommand("INSERT INTO assistant_migrations(version,digest) VALUES(@version,@digest)", connection, transaction); record.Parameters.AddWithValue("version",version); record.Parameters.AddWithValue("digest", digest); await record.ExecuteNonQueryAsync(token);
        }
        }
        await transaction.CommitAsync(token);
    }
    public async Task Stage(string manifestPath, CancellationToken token)
    {
        RejectLinks(Path.GetFullPath(manifestPath));
        var manifestBytes = await System.IO.File.ReadAllBytesAsync(manifestPath, token);
        if (manifestBytes.Length > 8192) throw new InvalidDataException("Manifest too large");
        var m = JsonSerializer.Deserialize<DocumentManifest>(manifestBytes, new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ?? throw new InvalidDataException("Invalid manifest");
        Validate(m);
        var path = ResolveDocumentPath(manifestPath,m.File);
        if (new FileInfo(path).Length > 1048576) throw new InvalidDataException("Document too large");
        var bytes = await System.IO.File.ReadAllBytesAsync(path, token); if (bytes.Length > 1048576) throw new InvalidDataException("Document too large");
        var text = new UTF8Encoding(false, true).GetString(bytes).Normalize(NormalizationForm.FormC); var chunks = Chunk(text); var hash = Digest(bytes);
        await using var connection = await source.OpenConnectionAsync(token); await using var transaction = await connection.BeginTransactionAsync(token);
        await LockIndex(connection,transaction,m.IndexVersion,token);
        await using(var publication=new NpgsqlCommand("SELECT EXISTS(SELECT 1 FROM published_indexes WHERE index_version=@index) AND NOT EXISTS(SELECT 1 FROM policy_documents WHERE document_id=@id AND version=@version)",connection,transaction))
        {
            publication.Parameters.AddWithValue("index",m.IndexVersion);publication.Parameters.AddWithValue("id",m.DocumentId);publication.Parameters.AddWithValue("version",m.Version);
            if((bool)(await publication.ExecuteScalarAsync(token)??false))throw new InvalidDataException("Published index cannot receive new documents or versions");
        }
        await using var insert = new NpgsqlCommand("""
            INSERT INTO policy_documents(document_id,version,digest,content,provenance,effective_from,effective_to,supersedes,index_version)
            VALUES(@id,@version,@digest,@content,@provenance,@from,@to,@supersedes,@index)
            ON CONFLICT(document_id,version) DO NOTHING RETURNING document_id
            """, connection, transaction);
        Add(insert, m, hash, text); var inserted = await insert.ExecuteScalarAsync(token);
        if (inserted is null)
        {
            await using var lookup = new NpgsqlCommand("SELECT digest,provenance,effective_from,effective_to,supersedes,index_version FROM policy_documents WHERE document_id=@id AND version=@version", connection, transaction);
            lookup.Parameters.AddWithValue("id", m.DocumentId); lookup.Parameters.AddWithValue("version", m.Version);
            await using var reader = await lookup.ExecuteReaderAsync(token); if (!await reader.ReadAsync(token) || reader.GetString(0) != hash || reader.GetString(1) != m.Provenance || reader.GetFieldValue<DateTimeOffset>(2) != m.EffectiveFrom || (reader.IsDBNull(3) ? (DateTimeOffset?)null : reader.GetFieldValue<DateTimeOffset>(3)) != m.EffectiveTo || (reader.IsDBNull(4) ? null : reader.GetString(4)) != m.Supersedes || reader.GetString(5) != m.IndexVersion) throw new InvalidDataException("Document/version conflict");
        }
        else
        {
            for (var i=0;i<chunks.Count;i++)
            {
                await using var chunk = new NpgsqlCommand("INSERT INTO policy_chunks(document_id,version,ordinal,section,content) VALUES(@id,@version,@ordinal,@section,@content)", connection, transaction);
                chunk.Parameters.AddWithValue("id",m.DocumentId); chunk.Parameters.AddWithValue("version",m.Version); chunk.Parameters.AddWithValue("ordinal",i); chunk.Parameters.AddWithValue("section",chunks[i].Section); chunk.Parameters.AddWithValue("content",chunks[i].Text); await chunk.ExecuteNonQueryAsync(token);
            }
        }
        await transaction.CommitAsync(token);
    }
    public async Task Activate(string index, CancellationToken token,string retrievalMode)
    {
        if (index.Length is 0 or > 64 || retrievalMode is not ("lexical" or "hybrid")) throw new InvalidDataException("Explicit lexical or hybrid activation mode required");
        await using var connection = await source.OpenConnectionAsync(token); await using var transaction = await connection.BeginTransactionAsync(token);
        await LockIndex(connection,transaction,index,token);
        await using var check = new NpgsqlCommand("SELECT count(*) FROM policy_documents d WHERE index_version=@index AND NOT EXISTS(SELECT 1 FROM policy_chunks c WHERE c.document_id=d.document_id AND c.version=d.version)",connection,transaction); check.Parameters.AddWithValue("index",index);
        if (Convert.ToInt64(await check.ExecuteScalarAsync(token)) != 0) throw new InvalidDataException("Incomplete index");
        if(retrievalMode=="hybrid")
        {
            await using var embeddings=new NpgsqlCommand("SELECT EXISTS(SELECT 1 FROM policy_chunks c JOIN policy_documents d USING(document_id,version) WHERE d.index_version=@index AND (c.embedding IS NULL OR c.embedding_model IS DISTINCT FROM 'text-embedding-3-small'))",connection,transaction);embeddings.Parameters.AddWithValue("index",index);
            if((bool)(await embeddings.ExecuteScalarAsync(token)??false))throw new InvalidDataException("Hybrid activation requires complete pinned embeddings");
        }
        await using var seal=new NpgsqlCommand("INSERT INTO published_indexes(index_version) SELECT @index WHERE EXISTS(SELECT 1 FROM policy_documents WHERE index_version=@index) ON CONFLICT DO NOTHING",connection,transaction);seal.Parameters.AddWithValue("index",index);await seal.ExecuteNonQueryAsync(token);
        await using var command = new NpgsqlCommand("INSERT INTO active_index(singleton,index_version) SELECT true,@index WHERE EXISTS(SELECT 1 FROM policy_documents WHERE index_version=@index) ON CONFLICT(singleton) DO UPDATE SET index_version=EXCLUDED.index_version RETURNING index_version",connection,transaction); command.Parameters.AddWithValue("index",index);
        if (await command.ExecuteScalarAsync(token) is null) throw new InvalidDataException("Unknown index"); await transaction.CommitAsync(token);
    }
    public async Task EmbedIndex(string index,OpenAiEmbeddings embeddings,TurnBudget budget,CancellationToken token)
    {
        // All provider calls occur before the activation transaction and before writes.
        await using var connection=await source.OpenConnectionAsync(token);
        await using var select=new NpgsqlCommand("SELECT c.document_id,c.version,c.ordinal,c.content FROM policy_chunks c JOIN policy_documents d USING(document_id,version) WHERE d.index_version=@index AND NOT EXISTS(SELECT 1 FROM published_indexes a WHERE a.index_version=@index) ORDER BY c.document_id,c.version,c.ordinal",connection);
        select.Parameters.AddWithValue("index",index);
        var rows=new List<(string Id,string Version,int Ordinal,string Text)>();
        await using(var reader=await select.ExecuteReaderAsync(token)) while(await reader.ReadAsync(token)) rows.Add((reader.GetString(0),reader.GetString(1),reader.GetInt32(2),reader.GetString(3)));
        if(rows.Count is 0 or >64) throw new InvalidDataException("Embedding requires inactive index with 1..64 chunks");
        var vectors=new List<string>(); foreach(var row in rows) vectors.Add(await embeddings.Embed(row.Text,budget,token));
        await using var transaction=await connection.BeginTransactionAsync(token);
        await LockIndex(connection,transaction,index,token);
        for(var i=0;i<rows.Count;i++)
        {
            await using var update=new NpgsqlCommand("UPDATE policy_chunks SET embedding=CAST(@vector AS vector),embedding_model=@model WHERE document_id=@id AND version=@version AND ordinal=@ordinal AND NOT EXISTS(SELECT 1 FROM published_indexes WHERE index_version=@index)",connection,transaction);
            update.Parameters.AddWithValue("vector",vectors[i]);update.Parameters.AddWithValue("model",OpenAiEmbeddings.Model);update.Parameters.AddWithValue("id",rows[i].Id);update.Parameters.AddWithValue("version",rows[i].Version);update.Parameters.AddWithValue("ordinal",rows[i].Ordinal);update.Parameters.AddWithValue("index",index);
            if(await update.ExecuteNonQueryAsync(token)!=1) throw new InvalidDataException("Index activated during staging");
        }
        await transaction.CommitAsync(token);
    }
    private static void Validate(DocumentManifest m)
    {
        if (m.DocumentId.Length is 0 or >128 || m.Version.Length is 0 or >64 || m.IndexVersion.Length is 0 or >64 || m.Provenance.Length is 0 or >2048 || m.EffectiveFrom.Offset != TimeSpan.Zero || m.EffectiveTo is not null && (m.EffectiveTo <= m.EffectiveFrom || m.EffectiveTo.Value.Offset != TimeSpan.Zero) || Path.GetExtension(m.File) is not (".md" or ".txt")) throw new InvalidDataException("Invalid document metadata");
        if(new[]{m.DocumentId,m.Version,m.IndexVersion}.Any(s=>s.Any(c=>!char.IsAsciiLetterOrDigit(c) && c is not ('-' or '_' or '.')))) throw new InvalidDataException("Invalid identifier");
    }
    private static async Task LockIndex(NpgsqlConnection connection,NpgsqlTransaction transaction,string index,CancellationToken token)
    {
        // hash collisions only serialize unrelated indexes; they never weaken exclusion.
        await using var command=new NpgsqlCommand("SELECT pg_advisory_xact_lock(hashtextextended(@index,791021))",connection,transaction);
        command.Parameters.AddWithValue("index",index);await command.ExecuteNonQueryAsync(token);
    }
    public static string ResolveDocumentPath(string manifestPath,string relativeFile)
    {
        var manifest=Path.GetFullPath(manifestPath);RejectLinks(manifest);
        if(Path.IsPathRooted(relativeFile) || relativeFile.Split('/', '\\').Any(p=>p is ".." or "." or ""))throw new InvalidDataException("Document must have a confined relative path");
        var root=Path.GetDirectoryName(manifest)!;var path=Path.GetFullPath(Path.Combine(root,relativeFile));
        var comparison=OperatingSystem.IsWindows()?StringComparison.OrdinalIgnoreCase:StringComparison.Ordinal;
        if(!path.StartsWith(root+Path.DirectorySeparatorChar,comparison))throw new InvalidDataException("Document escapes manifest directory");
        RejectLinks(path);return path;
    }
    private static void RejectLinks(string fullPath)
    {
        var cursor=fullPath;
        while(!string.IsNullOrEmpty(cursor))
        {
            if(System.IO.File.GetAttributes(cursor).HasFlag(FileAttributes.ReparsePoint))throw new InvalidDataException("Links are not accepted in policy paths");
            cursor=Path.GetDirectoryName(cursor);
        }
    }
    private static void Add(NpgsqlCommand c,DocumentManifest m,string digest,string content)
    {
        c.Parameters.AddWithValue("id",m.DocumentId);c.Parameters.AddWithValue("version",m.Version);c.Parameters.AddWithValue("digest",digest);c.Parameters.AddWithValue("content",content);c.Parameters.AddWithValue("provenance",m.Provenance);c.Parameters.AddWithValue("from",m.EffectiveFrom);
        c.Parameters.Add("to",NpgsqlTypes.NpgsqlDbType.TimestampTz).Value=m.EffectiveTo is null ? DBNull.Value : m.EffectiveTo.Value;
        c.Parameters.Add("supersedes",NpgsqlTypes.NpgsqlDbType.Text).Value=m.Supersedes is null ? DBNull.Value : m.Supersedes;
        c.Parameters.AddWithValue("index",m.IndexVersion);
    }
}

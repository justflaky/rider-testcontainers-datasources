using System;
using System.Threading.Tasks;
using Testcontainers.PostgreSql;
using Testcontainers.Redis;
using Xunit;

namespace TcDemo.Tests;

public class PostgresTests
{
    [Fact]
    public async Task Container_comes_up_and_disappears()
    {
        var postgres = new PostgreSqlBuilder()
            .WithImage("postgres:16-alpine")
            .WithDatabase("appdb")
            .WithUsername("app")
            .WithPassword("secret123")
            .Build();

        await postgres.StartAsync();
        try
        {
            // give the plugin (and anyone watching) a moment to see the container
            await Task.Delay(TimeSpan.FromSeconds(10));

            var connectionString = postgres.GetConnectionString();
            Assert.Contains("127.0.0.1", connectionString);
        }
        finally
        {
            await postgres.DisposeAsync();
        }
    }

    [Fact]
    public async Task Redis_container_comes_up_and_disappears()
    {
        var redis = new RedisBuilder()
            .WithImage("redis:7-alpine")
            .Build();

        await redis.StartAsync();
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(10));

            var connectionString = redis.GetConnectionString();
            Assert.Contains("127.0.0.1", connectionString);
        }
        finally
        {
            await redis.DisposeAsync();
        }
    }
}

namespace Minos.M24.Polyglot;

public interface IGreeter
{
    string Greet(string name);
}

public sealed class Greeter : IGreeter
{
    public string Greet(string name) => $"Hello {name}";
}

public static class Program
{
    public static int Main()
    {
        IGreeter greeter = new Greeter();
        return greeter.Greet("MINOS").Length > 0 ? 0 : 1;
    }
}

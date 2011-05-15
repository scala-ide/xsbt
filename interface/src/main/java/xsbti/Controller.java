package xsbti;

public interface Controller {
  void runInformUnitStarting(String phaseName, String unitPath);
  boolean runProgress(int current, int total); // false - if the compilation should be aborted
}

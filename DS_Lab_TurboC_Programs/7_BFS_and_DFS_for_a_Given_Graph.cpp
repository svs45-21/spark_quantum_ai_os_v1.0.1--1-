/* Program 7 : BFS and DFS for a given Graph (adjacency matrix)
               Vertices are numbered 1 to n (max 9)
   Compiler  : Turbo C++ 3.0                                       */
#include<iostream.h>
#include<conio.h>
#include<stdio.h>
#define MAX 10

int cost[MAX][MAX], n;

void bfs(int v)
{
  int qu[MAX], front=0, rear=0, visited[MAX], j;
  for(j=1;j<=n;j++)
    visited[j]=0;
  visited[v]=1;
  qu[rear++]=v;
  cout<<"BFS ORDER OF VISITED VERTICES: ";
  while(front<rear)
  {
    v=qu[front++];
    cout<<v<<" ";
    for(j=1;j<=n;j++)
    {
      if(cost[v][j]!=0 && visited[j]!=1)
      {
        visited[j]=1;
        qu[rear++]=j;
      }
    }
  }
  cout<<endl;
}

void dfs(int v)
{
  int stk[MAX*MAX], top=0, visited[MAX], j;
  for(j=1;j<=n;j++)
    visited[j]=0;
  stk[top++]=v;
  cout<<"DFS ORDER OF VISITED VERTICES: ";
  while(top>0)
  {
    v=stk[--top];
    if(visited[v]==1)
      continue;
    visited[v]=1;
    cout<<v<<" ";
    /* push neighbours in reverse order so smaller vertex is visited first */
    for(j=n;j>=1;j--)
    {
      if(cost[v][j]!=0 && visited[j]!=1)
        stk[top++]=j;
    }
  }
  cout<<endl;
}

void main()
{
  int m, i, j, k, v;
  clrscr();
  for(i=0;i<MAX;i++)
    for(j=0;j<MAX;j++)
      cost[i][j]=0;

  cout<<"Enter the no of vertices (max "<<MAX-1<<"): ";
  cin>>n;
  if(n>MAX-1)
    n=MAX-1;
  cout<<"Enter the no of edges: ";
  cin>>m;
  cout<<"\nEDGES (enter as: vertex1 vertex2)\n";
  for(k=1;k<=m;k++)
  {
    cin>>i>>j;
    if(i>=1 && i<=n && j>=1 && j<=n)
    {
      cost[i][j]=1;
      cost[j][i]=1;          /* undirected graph */
    }
    else
      cout<<"Invalid edge, ignored\n";
  }

  cout<<"\n*****BFS*****\n";
  cout<<"Enter initial vertex to traverse from: ";
  cin>>v;
  if(v>=1 && v<=n)
    bfs(v);
  else
    cout<<"Invalid vertex\n";

  cout<<"\n*****DFS*****\n";
  cout<<"Enter initial vertex to traverse from: ";
  cin>>v;
  if(v>=1 && v<=n)
    dfs(v);
  else
    cout<<"Invalid vertex\n";
  getch();
}
